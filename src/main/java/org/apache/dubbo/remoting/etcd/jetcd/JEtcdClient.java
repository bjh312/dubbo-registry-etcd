/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.etcd.jetcd;

import com.coreos.jetcd.api.Event;
import com.coreos.jetcd.api.KeyValue;
import com.coreos.jetcd.api.WatchCancelRequest;
import com.coreos.jetcd.api.WatchCreateRequest;
import com.coreos.jetcd.api.WatchGrpc;
import com.coreos.jetcd.api.WatchRequest;
import com.coreos.jetcd.api.WatchResponse;
import com.coreos.jetcd.common.exception.ClosedClientException;
import com.coreos.jetcd.data.ByteSequence;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.etcd.ChildListener;
import org.apache.dubbo.remoting.etcd.StateListener;
import org.apache.dubbo.remoting.etcd.option.OptionUtil;
import org.apache.dubbo.remoting.etcd.support.AbstractEtcdClient;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class JEtcdClient extends AbstractEtcdClient<JEtcdClient.EtcdWatcher> {

    private JEtcdClientWrapper clientWrapper;
    private ScheduledExecutorService reconnectSchedule;

    public JEtcdClient(URL url) {
        super(url);
        try{
            clientWrapper = new JEtcdClientWrapper(url);
            clientWrapper.setConnectionStateListener((client, state)->{
                if (state == StateListener.CONNECTED) {
                    JEtcdClient.this.stateChanged(StateListener.CONNECTED);
                } else if (state == StateListener.DISCONNECTED) {
                    JEtcdClient.this.stateChanged(StateListener.DISCONNECTED);
                }
            });
            reconnectSchedule = Executors.newScheduledThreadPool(1,
                                    new NamedThreadFactory("auto-reconnect"));
            clientWrapper.start();
        }catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void doCreatePersistent(String path) {
        clientWrapper.createPersistent(path);
    }

    @Override
    public long doCreateEphemeral(String path) {
        return clientWrapper.createEphemeral(path);
    }

    @Override
    public boolean checkExists(String path) {
        return clientWrapper.checkExists(path);
    }

    @Override
    public EtcdWatcher createChildWatcherListener(String path, ChildListener listener) {
        return new EtcdWatcher(listener);
    }

    @Override
    public List<String> addChildWatcherListener(String path, EtcdWatcher etcdWatcher) {
        return etcdWatcher.forPath(path);
    }

    @Override
    public void removeChildWatcherListener(String path, EtcdWatcher etcdWatcher) {
        etcdWatcher.unwatch();
    }

    @Override
    public List<String> getChildren(String path) {
        return clientWrapper.getChildren(path);
    }

    @Override
    public boolean isConnected() {
        return clientWrapper.isConnected();
    }

    @Override
    public long createLease(long second) {
        return clientWrapper.createLease(second);
    }

    @Override
    public long createLease(long ttl, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return clientWrapper.createLease(ttl, timeout, unit);
    }

    @Override
    public void delete(String path) {
        clientWrapper.delete(path);
    }

    @Override
    public void revokeLease(long lease) {
        clientWrapper.revokeLease(lease);
    }

    @Override
    public void doClose() {
        try{
            reconnectSchedule.shutdownNow();
        }catch (Exception e){

        }finally {
            clientWrapper.doClose();
        }
    }

    public class EtcdWatcher implements StreamObserver<WatchResponse> {

        private ChildListener listener;

        protected WatchGrpc.WatchStub watchStub;
        protected StreamObserver<WatchRequest> watchRequest;
        protected long watchId;
        protected String path;
        protected Throwable throwable;
        private AtomicInteger retry = new AtomicInteger();

        public EtcdWatcher(ChildListener listener) {
            this.listener = listener;
        }

        @Override
        public void onNext(WatchResponse response) {

            // prevents grpc on sending watchResponse to a closed watch client.
            if(!isConnected()){
                return;
            }

            watchId = response.getWatchId();

            if (listener != null) {
                Iterator<Event> iterator = response.getEventsList().iterator();
                while (iterator.hasNext()) {
                    Event event = iterator.next();
                    switch (event.getType()) {
                        case PUT:
                        case DELETE: {

                            KeyValue keyValue = event.getKv();
                            String key = keyValue.getKey().toStringUtf8();

                            int index = path.length(), count = 0;
                            if( key.length() >= index ){
                                for(; (index = key.indexOf(Constants.PATH_SEPARATOR, index )) != -1; ++index) {
                                    if (count++ > 1) break;
                                }
                            }

                            // if current path changed or direct path changed
                            if(path.equals(key) || count == 1){
                                // May be optimized in the future.
                                listener.childChanged(path, clientWrapper.getChildren(path));
                            }
                            break;
                        }
                        default: break;
                    }
                }
            }
        }

        @Override
        public void onError(Throwable e) {
            tryReconnect(e);
        }

        public void unwatch() {

            // prevents grpc on sending watchResponse to a closed watch client.
            if(!isConnected()){
                return;
            }

            try{
                this.listener = null;
                if(watchRequest != null) {
                    WatchCancelRequest watchCancelRequest =
                            WatchCancelRequest.newBuilder().setWatchId(watchId).build();
                    WatchRequest cancelRequest = WatchRequest.newBuilder()
                            .setCancelRequest(watchCancelRequest).build();
                    this.watchRequest.onNext(cancelRequest);
                }
            }catch (Exception ignored) {
                // ignore
            }
        }

        public List<String> forPath(String path) {

            if (!isConnected()) {
                throw new ClosedClientException("watch client has been closed, path '" + path + "'");
            }

            if(this.path != null) {
                if(this.path.equals(path)) {
                    return clientWrapper.getChildren(path);
                }
                unwatch();
            }

            this.watchStub = WatchGrpc.newStub(clientWrapper.getChannel());
            this.watchRequest = watchStub.watch(this);
            this.path = path;
            this.watchRequest.onNext(nextRequest());

            return clientWrapper.getChildren(path);
        }

        /**
         * create new watching request for current path.
         */
        protected WatchRequest nextRequest() {

            WatchCreateRequest.Builder builder = WatchCreateRequest.newBuilder()
                    .setKey(ByteString.copyFromUtf8(path))
                    .setRangeEnd(ByteString.copyFrom(
                            OptionUtil.prefixEndOf(ByteSequence.fromString(path)).getBytes()))
                    .setProgressNotify(true);

            return WatchRequest.newBuilder().setCreateRequest(builder).build();
        }

        public void tryReconnect(Throwable e) {

            this.throwable = e;

            logger.error("watcher client has error occurred, current path '" + path + "'",  e);

            // prevents grpc on sending error to a closed watch client.
            if (!isConnected()) {
                return;
            }

            Status status = Status.fromThrowable(e);
            // system may be recover later, current connect won't be lost
            if (OptionUtil.isHaltError(status) || OptionUtil.isNoLeaderError(status)) {
                this.closeWatchRequest();
                return;
            }
            // reconnect with a delay; avoiding immediate retry on a long connection downtime.
            reconnectSchedule.schedule(this::reconnect, 500, TimeUnit.MILLISECONDS);
        }

        protected synchronized void reconnect() {
            this.closeWatchRequest();
            this.recreateWatchRequest();
        }

        protected void recreateWatchRequest() {
            if(watchRequest == null) {
                this.watchStub = WatchGrpc.newStub(clientWrapper.getChannel());
                this.watchRequest = watchStub.watch(this);
            }
            this.watchRequest.onNext(nextRequest());
            this.throwable = null;
            logger.warn("watch client retried connect for path '" + path + "', connection status : " + isConnected());
        }

        protected void closeWatchRequest() {
            if (this.watchRequest == null) {
                return;
            }
            this.watchRequest.onCompleted();
            this.watchRequest = null;
        }

        @Override
        public void onCompleted() {
            // do not touch this method, if you want terminate this stream.
        }
    }

    private Logger logger = LoggerFactory.getLogger(JEtcdClient.class);
}
