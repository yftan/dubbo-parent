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
package com.alibaba.dubbo.rpc.protocol.thrift;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
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
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;
import com.alibaba.dubbo.rpc.protocol.dubbo.DubboExporter;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ThriftProtocol extends AbstractProtocol {

    /**
     * 默认端口号
     */
    public static final int DEFAULT_PORT = 40880;

    /**
     * 扩展名
     */
    public static final String NAME = "thrift";

    // ip:port -> ExchangeServer
    /**
     * 服务集合，key为ip:port
     */
    private final ConcurrentMap<String, ExchangeServer> serverMap =
            new ConcurrentHashMap<String, ExchangeServer>();

    private ExchangeHandler handler = new ExchangeHandlerAdapter() {

        @Override
        public Object reply(ExchangeChannel channel, Object msg) throws RemotingException {

            // 如果消息是Invocation类型的
            if (msg instanceof Invocation) {
                Invocation inv = (Invocation) msg;
                // 获得服务名
                String serviceName = inv.getAttachments().get(Constants.INTERFACE_KEY);
                // 获得服务的key
                String serviceKey = serviceKey(channel.getLocalAddress().getPort(),
                        serviceName, null, null);
                // 从集合中获得暴露者
                DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);
                // 如果暴露者为空，则抛出异常
                if (exporter == null) {
                    throw new RemotingException(channel,
                            "Not found exported service: "
                                    + serviceKey
                                    + " in "
                                    + exporterMap.keySet()
                                    + ", may be version or group mismatch "
                                    + ", channel: consumer: "
                                    + channel.getRemoteAddress()
                                    + " --> provider: "
                                    + channel.getLocalAddress()
                                    + ", message:" + msg);
                }

                // 设置远程地址
                RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
                return exporter.getInvoker().invoke(inv);

            }

            // 否则抛出异常，不支持的请求消息
            throw new RemotingException(channel,
                    "Unsupported request: "
                            + (msg.getClass().getName() + ": " + msg)
                            + ", channel: consumer: "
                            + channel.getRemoteAddress()
                            + " --> provider: "
                            + channel.getLocalAddress());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            // 如果消息是Invocation类型，则调用reply，否则接收消息
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            } else {
                super.received(channel, message);
            }
        }

    };

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {

        // can use thrift codec only
        // 只能使用thrift编解码器
        URL url = invoker.getUrl().addParameter(Constants.CODEC_KEY, ThriftCodec.NAME);
        // find server.
        // 获得服务地址
        String key = url.getAddress();
        // client can expose a service for server to invoke only.
        // 客户端可以为服务器暴露服务以仅调用
        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);
        if (isServer && !serverMap.containsKey(key)) {
            // 加入到集合
            serverMap.put(key, getServer(url));
        }
        // export service.
        // 得到服务key
        key = serviceKey(url);
        // 创建暴露者
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 加入集合
        exporterMap.put(key, exporter);

        return exporter;
    }

    @Override
    public void destroy() {

        super.destroy();

        // 遍历服务集合
        for (String key : new ArrayList<String>(serverMap.keySet())) {

            // 移除服务
            ExchangeServer server = serverMap.remove(key);

            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo server: " + server.getLocalAddress());
                    }
                    // 关闭服务
                    server.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            } // ~ end of if ( server != null )

        } // ~ end of loop serverMap

    } // ~ end of method destroy

    /**
     * 服务引用
     * @param type Service class 服务类名
     * @param url  URL address for the remote service
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {

        // 创建ThriftInvoker
        ThriftInvoker<T> invoker = new ThriftInvoker<T>(type, url, getClients(url), invokers);

        // 加入到集合
        invokers.add(invoker);

        return invoker;

    }

    private ExchangeClient[] getClients(URL url) {

        // 获得连接数
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 1);

        // 创建客户端集合
        ExchangeClient[] clients = new ExchangeClient[connections];

        // 创建客户端
        for (int i = 0; i < clients.length; i++) {
            clients[i] = initClient(url);
        }
        return clients;
    }

    /**
     * 创建客户端
     * @param url
     * @return
     */
    private ExchangeClient initClient(URL url) {

        ExchangeClient client;

        // 加上编解码器
        url = url.addParameter(Constants.CODEC_KEY, ThriftCodec.NAME);

        try {
            // 创建客户端
            client = Exchangers.connect(url);
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url
                    + "): " + e.getMessage(), e);
        }

        return client;

    }

    private ExchangeServer getServer(URL url) {
        // enable sending readonly event when server closes by default
        // 加入只读事件
        url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());
        // 获得服务的实现方式
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

        // 如果该实现方式不是dubbo支持的方式，则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str))
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);

        ExchangeServer server;
        try {
            // 获得服务器
            server = Exchangers.bind(url, handler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }
        // 获得实现方式
        str = url.getParameter(Constants.CLIENT_KEY);
        // 如果客户端实现方式不是dubbo支持的方式，则抛出异常。
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        return server;
    }

}
