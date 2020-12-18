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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.serialize.support.SerializableClassRegistry;
import com.alibaba.dubbo.common.serialize.support.SerializationOptimizer;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Transporter;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Exchangers;
import com.alibaba.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    /**
     * 默认端口号
     */
    public static final int DEFAULT_PORT = 20880;
    /**
     * 回调名称
     */
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    /**
     * dubbo协议的单例
     */
    private static DubboProtocol INSTANCE;
    /**
     * 信息交换服务器集合 key：host:port  value：ExchangeServer
     */
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<String, ExchangeServer>(); // <host:port,Exchanger>
    /**
     * 信息交换客户端集合
     */
    private final Map<String, ReferenceCountExchangeClient> referenceClientMap = new ConcurrentHashMap<String, ReferenceCountExchangeClient>(); // <host:port,Exchanger>
    /**
     * 懒加载的客户端集合
     */
    private final ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap = new ConcurrentHashMap<String, LazyConnectExchangeClient>();
    /**
     * 锁集合
     */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    /**
     * 序列化类名集合
     */
    private final Set<String> optimizers = new ConcurrentHashSet<String>();
    //consumer side export a stub service for dispatching event
    //servicekey-stubmethods
    /**
     * 本地存根服务方法集合
     */
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<String, String>();
    /**
     * 新建一个请求处理器
     */
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {
        /**
         * 回复请求结果，返回的是请求结果
         * @param channel
         * @param message
         * @return
         * @throws RemotingException
         */
        @Override
        public Object reply(ExchangeChannel channel, Object message) throws RemotingException {
            // 如果请求消息属于会话域
            if (message instanceof Invocation) {
                Invocation inv = (Invocation) message;
                // 获得暴露的invoker
                Invoker<?> invoker = getInvoker(channel, inv);
                // need to consider backward-compatibility if it's a callback
                // 如果是回调服务
                if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                    // 获得 方法定义
                    String methodsStr = invoker.getUrl().getParameters().get("methods");
                    boolean hasMethod = false;
                    // 判断看是否有会话域中的方法
                    if (methodsStr == null || methodsStr.indexOf(",") == -1) {
                        hasMethod = inv.getMethodName().equals(methodsStr);
                    } else {
                        // 如果方法不止一个，则分割后遍历查询，找到了则设置为true
                        String[] methods = methodsStr.split(",");
                        for (String method : methods) {
                            if (inv.getMethodName().equals(method)) {
                                hasMethod = true;
                                break;
                            }
                        }
                    }
                    // 如果没有该方法，则打印告警日志
                    if (!hasMethod) {
                        logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                                + " not found in callback service interface ,invoke will be ignored."
                                + " please update the api interface. url is:"
                                + invoker.getUrl()) + " ,invocation is :" + inv);
                        return null;
                    }
                }
                // 设置远程地址
                RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
                // 调用下一个调用链
                return invoker.invoke(inv);
            }
            // 否则抛出异常
            throw new RemotingException(channel, "Unsupported request: "
                    + (message == null ? null : (message.getClass().getName() + ": " + message))
                    + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
        }

        /**
         * 接收消息
         * @param channel
         * @param message
         * @throws RemotingException
         */
        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            // 如果消息是会话域中的消息，则调用reply方法。
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            // 接收连接事件
            invoke(channel, Constants.ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isInfoEnabled()) {
                logger.info("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            // 接收断开连接事件
            invoke(channel, Constants.ON_DISCONNECT_KEY);
        }

        /**
         * 接收事件
         * @param channel
         * @param methodKey
         */
        private void invoke(Channel channel, String methodKey) {
            // 创建会话域
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    // 接收事件
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        /**
         * 创建会话域, 把url内的值加入到会话域的附加值中
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            // 获得方法，methodKey是onconnect或者ondisconnect
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }
            // 创建一个rpc会话域
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            // 加入附加值path
            invocation.setAttachment(Constants.PATH_KEY, url.getPath());
            // 加入附加值group
            invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            // 加入附加值interface
            invocation.setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            // 加入附加值version
            invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
            // 如果是本地存根服务，则加入附加值dubbo.stub.event为true
            if (url.getParameter(Constants.STUB_EVENT_KEY, false)) {
                invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
            }
            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME); // load
        }
        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    /**
     * 判断是否是客户端侧的
     * @param channel
     * @return
     */
    private boolean isClientSide(Channel channel) {
        // 获得远程地址
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    /**
     * 从会话域中获得invoker对象
     * @param channel
     * @param inv
     * @return
     * @throws RemotingException
     */
    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        // 获得本地端口
        int port = channel.getLocalAddress().getPort();
        // 从会话域中获得附加值path
        String path = inv.getAttachments().get(Constants.PATH_KEY);
        // if it's callback service on client side
        // 是否是本地存根服务
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(Constants.STUB_EVENT_KEY));
        // 如果是本地存根服务，则端口号是远程地址的端口
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }
        //callback
        // 是否是回调服务
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        // 如果是，则path还要加上
        if (isCallBackServiceInvoke) {
            path = inv.getAttachments().get(Constants.PATH_KEY) + "." + inv.getAttachments().get(Constants.CALLBACK_SERVICE_KEY);
            // 把标志加入到附加值中
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }
        // 得到服务key  group+"/"+serviceName+":"+serviceVersion+":"+port
        String serviceKey = serviceKey(port, path, inv.getAttachments().get(Constants.VERSION_KEY), inv.getAttachments().get(Constants.GROUP_KEY));

        // 根据服务key从集合中获得服务暴露者
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        // 如果为空则抛出异常
        if (exporter == null)
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);

        // 返回invoker
        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    /**
     * 返回默认的端口号
     * @return
     */
    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 暴露服务
     * @param invoker Service invoker 服务的实体域
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();

        // export service.
        // 得到服务key  group+"/"+serviceName+":"+serviceVersion+":"+port
        String key = serviceKey(url);
        // 创建exporter
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 加入到集合
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY, Constants.DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
        // 如果是本地存根事件而不是回调服务
        if (isStubSupportEvent && !isCallbackservice) {
            // 获得本地存根的方法
            String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
            // 如果为空，则抛出异常
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(Constants.INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }
            } else {
                // 加入集合
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }


        // 打开服务
        openServer(url);
        // 序列化
        optimizeSerialization(url);
        return exporter;
    }

    /**
     * 打开服务
     * @param url
     */
    private void openServer(URL url) {
        // find server.
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        // 客户端是否可以暴露仅供服务器调用的服务
        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);
        // 如果是的话
        if (isServer) {
            // 获得信息交换服务器
            ExchangeServer server = serverMap.get(key);
            if (server == null) {
                // 重新创建服务器对象，然后放入集合
                serverMap.put(key, createServer(url));
            } else {
                // server supports reset, use together with override
                // 重置
                server.reset(url);
            }
        }
    }

    /**
     * 创建服务器
     * @param url
     * @return
     */
    private ExchangeServer createServer(URL url) {
        // send readonly event when server closes, it's enabled by default
        // 服务器关闭时发送readonly事件，默认情况下启用
        url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());
        // enable heartbeat by default
        // 心跳默认间隔一分钟
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));
        // 获得远程通讯服务端实现方式，默认用netty3
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

        /**
         * 如果没有该配置，则抛出异常
         */
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str))
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);

        /**
         * 添加编解码器DubboCodec实现
         */
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        ExchangeServer server;
        try {
            // 启动服务器
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }
        // 获得客户端侧设置的远程通信方式
        str = url.getParameter(Constants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            // 获得远程通信的实现集合
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            // 如果客户端侧设置的远程通信方式不在支持的方式中，则抛出异常
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        return server;
    }

    /**
     * 优化序列化
     * @param url
     * @throws RpcException
     */
    private void optimizeSerialization(URL url) throws RpcException {
        // 获得类名
        String className = url.getParameter(Constants.OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            // 加载类
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            // 强制类型转化为SerializationOptimizer
            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            // 遍历序列化的类，把该类放入到集合进行缓存
            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            // 加入到集合
            optimizers.add(className);
        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);
        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    /**
     * 服务引用
     * @param serviceType
     * @param url  URL address for the remote service
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        // 序列化
        optimizeSerialization(url);
        // create rpc invoker. 创建一个DubboInvoker对象
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        // 把该invoker放入集合
        invokers.add(invoker);
        return invoker;
    }

    /**
     * 获得客户端集合
     * @param url
     * @return
     */
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        // 一个连接是否对于一个服务
        boolean service_share_connect = false;
        // 获得url中欢愉连接共享的配置 默认为0
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
        // if not configured, connection is shared, otherwise, one connection for one service
        // 如果为0，则是共享类，并且连接数为1
        if (connections == 0) {
            service_share_connect = true;
            connections = 1;
        }

        // 创建数组
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            // 如果共享，则获得共享客户端对象，否则新建客户端
            if (service_share_connect) {
                clients[i] = getSharedClient(url);
            } else {
                clients[i] = initClient(url);
            }
        }
        return clients;
    }

    /**
     * Get shared connection
     * 获得分享的客户端连接
     */
    private ExchangeClient getSharedClient(URL url) {
        String key = url.getAddress();
        // 从集合中取出客户端对象
        ReferenceCountExchangeClient client = referenceClientMap.get(key);
        // 如果不为空并且没关闭连接，则计数器加1，返回
        if (client != null) {
            if (!client.isClosed()) {
                client.incrementAndGetCount();
                return client;
            } else {
                // 如果连接断开，则从集合中移除
                referenceClientMap.remove(key);
            }
        }

        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            // 如果集合中有该key
            if (referenceClientMap.containsKey(key)) {
                // 则直接返回client
                return referenceClientMap.get(key);
            }

            // 否则新建一个连接
            ExchangeClient exchangeClient = initClient(url);
            client = new ReferenceCountExchangeClient(exchangeClient, ghostClientMap);
            // 存入集合
            referenceClientMap.put(key, client);
            // 从ghostClientMap中移除
            ghostClientMap.remove(key);
            // 从对象锁中移除
            locks.remove(key);
            return client;
        }
    }

    /**
     * Create new connection
     * 新建客户端连接
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        // 获得客户端的实现方法 默认netty3
        String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

        // 添加编码器
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        // 默认开启心跳
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            // 是否需要延迟连接，，默认不开启
            if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
                // 创建延迟连接的客户端
                client = new LazyConnectExchangeClient(url, requestHandler);
            } else {
                // 否则就直接连接
                client = Exchangers.connect(url, requestHandler);
            }
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }
        return client;
    }

    @Override
    public void destroy() {
        // 遍历服务器逐个关闭
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo server: " + server.getLocalAddress());
                    }
                    server.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        // 遍历客户端集合逐个关闭
        for (String key : new ArrayList<String>(referenceClientMap.keySet())) {
            ExchangeClient client = referenceClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        // 遍历懒加载的集合，逐个关闭客户端
        for (String key : new ArrayList<String>(ghostClientMap.keySet())) {
            ExchangeClient client = ghostClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        stubServiceMethodsMap.clear();
        super.destroy();
    }
}
