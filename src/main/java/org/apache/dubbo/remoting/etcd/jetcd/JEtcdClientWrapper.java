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

import com.coreos.jetcd.Client;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.options.GetOption;
import com.coreos.jetcd.options.PutOption;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.util.RoundRobinLoadBalancerFactory;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.concurrent.ListenableFutureTask;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.etcd.StateListener;
import org.apache.dubbo.remoting.etcd.option.OptionUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static java.util.stream.Collectors.toList;

public class JEtcdClientWrapper {

    private Logger logger = LoggerFactory.getLogger(JEtcdClientWrapper.class);

    private final URL url;
    private Client client;
    private volatile boolean started = false;
    private volatile boolean connectState = false;
    private ScheduledFuture future;
    private ScheduledExecutorService reconnectNotify;
    private AtomicReference<ManagedChannel> channel;

    private ConnectionStateListener connectionStateListener;

    private long expirePeriod;

    private ListenableFutureTask<Client> listenableFutureTask;

    public JEtcdClientWrapper(URL url) {
        this.url = url;
        this.expirePeriod = url.getParameter(Constants.SESSION_TIMEOUT_KEY, Constants.DEFAULT_KEEPALIVE_TIMEOUT) / 1000;
        if (expirePeriod <= 0) {
            this.expirePeriod = (Constants.DEFAULT_KEEPALIVE_TIMEOUT) / 1000;
        }
        this.channel = new AtomicReference<>();

        this.listenableFutureTask = ListenableFutureTask.create(() -> {
            return Client.builder()
                    .loadBalancerFactory(RoundRobinLoadBalancerFactory.getInstance())
                    .endpoints(endPoints(url.getBackupAddress())).build();
        });
        this.reconnectNotify = Executors.newScheduledThreadPool(1,
                new NamedThreadFactory("reconnectNotify", true));
    }

    public Client getClient() {
        return client;
    }

    /**
     * try to get current connected channel.
     *
     * @return connected channel.
     */
    public ManagedChannel getChannel() {
        if (channel.get() == null || (channel.get().isShutdown() || channel.get().isTerminated())) {
            channel.set(newChannel(client));
        }
        return channel.get();
    }

    /**
     * find direct children directory, excluding path self,
     * Never return null.
     *
     * @param path the path to be found direct children.
     * @return direct children directory, contains zero element
     * list if children directory not exists.
     */
    public List<String> getChildren(String path) {
        for (; ; ) {
            try {
                int len = path.length();
                return client.getKVClient()
                        .get(ByteSequence.fromString(path),
                                GetOption.newBuilder().withPrefix(ByteSequence.fromString(path)).build()).get()
                        .getKvs().stream().parallel()
                        .filter(pair -> {
                            String key = pair.getKey().toStringUtf8();
                            int index = len, count = 0;
                            if (key.length() > len) {
                                for (; (index = key.indexOf(Constants.PATH_SEPARATOR, index)) != -1; ++index) {
                                    if (count++ > 1) break;
                                }
                            }
                            return count == 1;
                        })
                        .map(pair -> pair.getKey().toStringUtf8())
                        .collect(toList());
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to get children by path '" + path + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    public boolean isConnected() {
        return !(getChannel().isShutdown()
                || getChannel().isTerminated());
    }

    public long createLease(long second) {
        for (; ; ) {
            try {
                return client.getLeaseClient()
                        .grant(second).get().getID();
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to create lease grant second '" + second + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    public void revokeLease(long lease) {
        for (; ; ) {
            try {
                client.getLeaseClient()
                        .revoke(lease).get();
                break;
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.NOT_FOUND) {
                    break;
                }
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to revoke lease '" + lease + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    public long createLease(long ttl, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {

        if (timeout <= 0) {
            return createLease(ttl);
        }

        return client.getLeaseClient()
                .grant(ttl)
                .get(timeout, unit).getID();
    }


    /**
     * try to check if path exists.
     */
    public boolean checkExists(String path) {
        for (; ; ) {
            try {
                return client.getKVClient()
                        .get(ByteSequence.fromString(path), GetOption.newBuilder().withCountOnly(true).build())
                        .get().getCount() > 0;
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to check exists by path '" + path + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    /**
     * only internal use only, maybe change in the future
     */
    protected Long find(String path) {
        for (; ; ) {
            try {
                return client.getKVClient()
                        .get(ByteSequence.fromString(path)).get()
                        .getKvs().stream()
                        .mapToLong(keyValue -> Long.valueOf(keyValue.getValue().toStringUtf8()))
                        .findFirst().getAsLong();
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to find path by path '" + path + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    public void createPersistent(String path) {
        for (; ; ) {
            try {
                client.getKVClient()
                        .put(ByteSequence.fromString(path),
                                ByteSequence.fromString(String.valueOf(path.hashCode()))).get();
                break;
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to create persistent  by path '" + path + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    /**
     * create new ephemeral path save to etcd .
     * if node disconnect from etcd, it will be deleted
     * automatically by etcd when sessian timeout.
     *
     * @param path the path to be saved
     * @return the lease of current path.
     */
    public long createEphemeral(String path) {
        for (; ; ) {
            try {
                long lease = client.getLeaseClient().grant(expirePeriod).get().getID();
                keepAlive(lease);
                client.getKVClient()
                        .put(ByteSequence.fromString(path)
                                , ByteSequence.fromString(String.valueOf(lease))
                                , PutOption.newBuilder().withLeaseId(lease).build()).get();
                return lease;
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to create ephereral by path '" + path + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    // easy for mock
    public void keepAlive(long lease) {
        client.getLeaseClient().keepAlive(lease);
    }

    public void delete(String path) {
        for (; ; ) {
            try {
                client.getKVClient()
                        .delete(ByteSequence.fromString(path)).get();
                break;
            } catch (Exception e) {
                Status status = Status.fromThrowable(e);
                if (status != null && status.getCode() == Status.Code.UNKNOWN) {
                    throw new IllegalStateException("failed to delete by path '" + path + "'", e);
                }
                if (OptionUtil.isRecoverable(status)) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    continue;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    public List<String> endPoints(String backupAddress) {
        String[] endpoints = backupAddress.split(Constants.COMMA_SEPARATOR);
        return Arrays.stream(endpoints)
                .map(address -> address.indexOf(Constants.HTTP_SUBFIX_KEY) > -1
                        ? address
                        : Constants.HTTP_KEY + address)
                .collect(toList());
    }

    /**
     * because jetcd's connection change callback not supported yet, we must
     * loop to test if connect or disconnect event happend or not. It will be changed
     * in the future if we found better choice.
     */
    public void start() {
        if (!started) {
            try {

                Thread connectThread = new Thread(listenableFutureTask);
                connectThread.setName("DubboEtcdClientConnector");
                connectThread.setDaemon(true);
                connectThread.start();
                this.client = listenableFutureTask.get(expirePeriod, TimeUnit.SECONDS);
                this.connectState = isConnected();
                this.started = true;
            } catch (Throwable t) {
                logger.error("Etcd3 server can not be connected in " + expirePeriod + " second! etcd3 address: " + endPoints(url.getBackupAddress()), t);
            }

            try {
                int retry = 3;
                this.future = reconnectNotify.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        boolean state = isConnected();
                        if (state != connectState) {
                            int notifyState = state ? StateListener.CONNECTED : StateListener.DISCONNECTED;
                            if (connectionStateListener != null) {
                                connectionStateListener.stateChanged(getClient(), notifyState);
                            }
                            connectState = state;
                        }
                    }
                }, retry, retry, TimeUnit.SECONDS);
            } catch (Throwable t) {
                logger.error("monitor reconnect status failed.", t);
            }
        }
    }

    protected void doClose() {
        try {
            if (started && future != null) {
                started = false;
                future.cancel(true);
                reconnectNotify.shutdownNow();
            }
        } catch (Exception e) {
            logger.warn("stop reconnect Notify failed.", e);
        }
        if (getClient() != null) getClient().close();
    }

    /**
     * try get client's shared channel, becase all fields is private on jetcd,
     * we must using it by reflect, in the future, jetcd may provider better tools.
     *
     * @param client get channel from current client
     * @return current connection channel
     */
    private ManagedChannel newChannel(Client client) {
        try {
            Field connectionField = client.getClass().getDeclaredField("connectionManager");
            if (!connectionField.isAccessible()) {
                connectionField.setAccessible(true);
            }
            Object connection = connectionField.get(client);
            Method channel = connection.getClass().getDeclaredMethod("getChannel");
            if (!channel.isAccessible()) {
                channel.setAccessible(true);
            }
            return (ManagedChannel) channel.invoke(connection);
        } catch (Exception e) {
            throw new RuntimeException("get connection channel failed from " + url.getBackupAddress(), e);
        }
    }

    public ConnectionStateListener getConnectionStateListener() {
        return connectionStateListener;
    }

    public void setConnectionStateListener(ConnectionStateListener connectionStateListener) {
        this.connectionStateListener = connectionStateListener;
    }

    public interface ConnectionStateListener {
        /**
         * Called when there is a state change in the connection
         *
         * @param client   the client
         * @param newState the new state
         */
        public void stateChanged(Client client, int newState);
    }
}
