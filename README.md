# dubbo-registry-etcd3
etcd3 registry for dubbo framework

## 使用etcd3
单机etcd：
```xml
<dubbo:registry address="etcd3://127.0.0.1:2379"/>
```

集群etcd3:
```xml
<dubbo:registry address="ip:port,ip:port,..." protocol="etcd3"/>
```

## etcd3注册中心配置简介

这里讲解的启动`etcd3`主要方便本地运行单元测试跟踪内部细节，集群启动也是在本地启动的伪集群。

当前实现是针对`etcd3`，需要指定环境变量，在mac"中`~/.bash_profile`添加：

```shell
# etcd
export ETCDCTL_API=3
```

如果使zsh的shell，把`~/.bash_profile`加进去：

```shell
source /Users/yourUserName/.bash_profile
```

添加这个环境变量是告诉etcdctl客户端使用高版本的api。

## 安装etcd

进入[etcd安装包](https://github.com/coreos/etcd/releases)选择对应平台的安装包，以mac为例:

``` shell
1. 解压缩把etcd和etcdctl拷贝到`/usr/local/bin`目录
2. 确保`/usr/local/bin`在path环境变量存在

如果不存在，把下面放到`~/.bash_profile`中

PATH="${PATH}:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/Jason/go/bin"
export PATH

```


## 单机启动etcd

因为配置好了变量，在终端直接执行：

```
~ etcd
```

启动后会监听2389端口，输出日志：

```
2018-03-02 10:44:48.864928 N | embed: serving insecure client requests on 127.0.0.1:2379, this is strongly discouraged!
```

## 集群启动etcd

在开发环境安装本地集群：

1. 安装[goreman](https://github.com/mattn/goreman)

```shell
go get github.com/mattn/goreman
```

2. 添加环境变量

``` shell
把下面放到`~/.bash_profile`中
PATH="${PATH}:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/yourUserName/go/bin"
export PATH
```

第1步安装goreman建议的~目录，会生成/Users/yourUserName/go/bin，里面包含goreman。


3. 拷贝[Procfile](../Procfile)到用户目录~

    Procfile文件内容：

````
# Use goreman to run `go get github.com/mattn/goreman`
etcd1: etcd --name infra1 --listen-client-urls http://127.0.0.1:2379 --advertise-client-urls http://127.0.0.1:2379 --listen-peer-urls http://127.0.0.1:12380 --initial-advertise-peer-urls http://127.0.0.1:12380 --initial-cluster-token etcd-cluster-1 --initial-cluster 'infra1=http://127.0.0.1:12380,infra2=http://127.0.0.1:22380,infra3=http://127.0.0.1:32380' --initial-cluster-state new --enable-pprof
etcd2: etcd --name infra2 --listen-client-urls http://127.0.0.1:22379 --advertise-client-urls http://127.0.0.1:22379 --listen-peer-urls http://127.0.0.1:22380 --initial-advertise-peer-urls http://127.0.0.1:22380 --initial-cluster-token etcd-cluster-1 --initial-cluster 'infra1=http://127.0.0.1:12380,infra2=http://127.0.0.1:22380,infra3=http://127.0.0.1:32380' --initial-cluster-state new --enable-pprof
etcd3: etcd --name infra3 --listen-client-urls http://127.0.0.1:32379 --advertise-client-urls http://127.0.0.1:32379 --listen-peer-urls http://127.0.0.1:32380 --initial-advertise-peer-urls http://127.0.0.1:32380 --initial-cluster-token etcd-cluster-1 --initial-cluster 'infra1=http://127.0.0.1:12380,infra2=http://127.0.0.1:22380,infra3=http://127.0.0.1:32380' --initial-cluster-state new --enable-pprof
#proxy: bin/etcd grpc-proxy start --endpoints=127.0.0.1:2379,127.0.0.1:22379,127.0.0.1:32379 --listen-addr=127.0.0.1:23790 --advertise-client-url=127.0.0.1:23790 --enable-pprof
````

4. 启动集群

```shell
 ~ goreman start
```

启动后会监听2379、22379、32379，输出日志：

```
10:57:26 etcd1 | 2018-03-02 10:57:26.143536 N | embed: serving insecure client requests on 127.0.0.1:2379, this is strongly discouraged!
10:57:26 etcd2 | 2018-03-02 10:57:26.152223 N | embed: serving insecure client requests on 127.0.0.1:22379, this is strongly discouraged!
10:57:26 etcd3 | 2018-03-02 10:57:26.143620 N | embed: serving insecure client requests on 127.0.0.1:32379, this is strongly discouraged!
```
