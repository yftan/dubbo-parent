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
package com.alibaba.dubbo.rpc.protocol.rest;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.http.HttpBinder;
import com.alibaba.dubbo.remoting.http.servlet.BootstrapListener;
import com.alibaba.dubbo.remoting.http.servlet.ServletManager;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.StaticContext;
import com.alibaba.dubbo.rpc.protocol.AbstractProxyProtocol;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;
import org.jboss.resteasy.util.GetRestful;

import javax.servlet.ServletContext;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RestProtocol extends AbstractProxyProtocol {

    /**
     * 默认端口号
     */
    private static final int DEFAULT_PORT = 80;

    /**
     * 服务器集合
     */
    private final Map<String, RestServer> servers = new ConcurrentHashMap<String, RestServer>();

    /**
     * 服务器工厂
     */
    private final RestServerFactory serverFactory = new RestServerFactory();

    // TODO in the future maybe we can just use a single rest client and connection manager
    /**
     * 客户端集合
     */
    private final List<ResteasyClient> clients = Collections.synchronizedList(new LinkedList<ResteasyClient>());

    /**
     * 连接监控
     */
    private volatile ConnectionMonitor connectionMonitor;

    public RestProtocol() {
        super(WebApplicationException.class, ProcessingException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        serverFactory.setHttpBinder(httpBinder);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 暴露服务
     * @param impl
     * @param type
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException {
        // 获得地址
        String addr = getAddr(url);
        // 获得实现类
        Class implClass = (Class) StaticContext.getContext(Constants.SERVICE_IMPL_CLASS).get(url.getServiceKey());
        // 获得服务
        RestServer server = servers.get(addr);
        if (server == null) {
            // 创建服务器
            server = serverFactory.createServer(url.getParameter(Constants.SERVER_KEY, "jetty"));
            // 开启服务器
            server.start(url);
            // 加入集合
            servers.put(addr, server);
        }

        // 获得contextPath
        String contextPath = getContextPath(url);
        // 如果以servlet的方式
        if ("servlet".equalsIgnoreCase(url.getParameter(Constants.SERVER_KEY, "jetty"))) {
            // 获得ServletContext
            ServletContext servletContext = ServletManager.getInstance().getServletContext(ServletManager.EXTERNAL_SERVER_PORT);
            // 如果为空，则抛出异常
            if (servletContext == null) {
                throw new RpcException("No servlet context found. Since you are using server='servlet', " +
                        "make sure that you've configured " + BootstrapListener.class.getName() + " in web.xml");
            }
            String webappPath = servletContext.getContextPath();
            if (StringUtils.isNotEmpty(webappPath)) {
                // 检测配置是否正确
                webappPath = webappPath.substring(1);
                if (!contextPath.startsWith(webappPath)) {
                    throw new RpcException("Since you are using server='servlet', " +
                            "make sure that the 'contextpath' property starts with the path of external webapp");
                }
                contextPath = contextPath.substring(webappPath.length());
                if (contextPath.startsWith("/")) {
                    contextPath = contextPath.substring(1);
                }
            }
        }

        // 获得资源
        final Class resourceDef = GetRestful.getRootResourceClass(implClass) != null ? implClass : type;

        // 部署服务器
        server.deploy(resourceDef, impl, contextPath);

        final RestServer s = server;
        return new Runnable() {
            @Override
            public void run() {
                // TODO due to dubbo's current architecture,
                // it will be called from registry protocol in the shutdown process and won't appear in logs
                s.undeploy(resourceDef);
            }
        };
    }

    /**
     * 服务引用
     * @param serviceType
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> T doRefer(Class<T> serviceType, URL url) throws RpcException {
        // 如果连接监控为空，则创建
        if (connectionMonitor == null) {
            connectionMonitor = new ConnectionMonitor();
        }

        // TODO more configs to add
        // 创建http连接池
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        // 20 is the default maxTotal of current PoolingClientConnectionManager
        // 最大连接数
        connectionManager.setMaxTotal(url.getParameter(Constants.CONNECTIONS_KEY, 20));
        // 最大的路由数
        connectionManager.setDefaultMaxPerRoute(url.getParameter(Constants.CONNECTIONS_KEY, 20));

        // 添加监控
        connectionMonitor.addConnectionManager(connectionManager);
        // 新建请求配置
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT))
                .setSocketTimeout(url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT))
                .build();

        // 设置socket配置
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoKeepAlive(true)
                .setTcpNoDelay(true)
                .build();

        // 创建http客户端
        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setKeepAliveStrategy(new ConnectionKeepAliveStrategy() {
                    @Override
                    public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
                        HeaderElementIterator it = new BasicHeaderElementIterator(response.headerIterator(HTTP.CONN_KEEP_ALIVE));
                        while (it.hasNext()) {
                            HeaderElement he = it.nextElement();
                            String param = he.getName();
                            String value = he.getValue();
                            if (value != null && param.equalsIgnoreCase("timeout")) {
                                return Long.parseLong(value) * 1000;
                            }
                        }
                        // TODO constant
                        return 30 * 1000;
                    }
                })
                .setDefaultRequestConfig(requestConfig)
                .setDefaultSocketConfig(socketConfig)
                .build();

        // 创建ApacheHttpClient4Engine对应，为了使用resteasy
        ApacheHttpClient4Engine engine = new ApacheHttpClient4Engine(httpClient/*, localContext*/);

        //  创建ResteasyClient对象
        ResteasyClient client = new ResteasyClientBuilder().httpEngine(engine).build();
        // 加入集合
        clients.add(client);

        // 设置过滤器
        client.register(RpcContextFilter.class);
        // 注册各类组件
        for (String clazz : Constants.COMMA_SPLIT_PATTERN.split(url.getParameter(Constants.EXTENSION_KEY, ""))) {
            if (!StringUtils.isEmpty(clazz)) {
                try {
                    client.register(Thread.currentThread().getContextClassLoader().loadClass(clazz.trim()));
                } catch (ClassNotFoundException e) {
                    throw new RpcException("Error loading JAX-RS extension class: " + clazz.trim(), e);
                }
            }
        }

        // TODO protocol
        // 创建 Service Proxy 对象。
        ResteasyWebTarget target = client.target("http://" + url.getHost() + ":" + url.getPort() + "/" + getContextPath(url));
        return target.proxy(serviceType);
    }

    @Override
    protected int getErrorCode(Throwable e) {
        // TODO
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();

        // 关闭监控
        if (connectionMonitor != null) {
            connectionMonitor.shutdown();
        }

        for (Map.Entry<String, RestServer> entry : servers.entrySet()) {
            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Closing the rest server at " + entry.getKey());
                }
                // 停止每个服务
                entry.getValue().stop();
            } catch (Throwable t) {
                logger.warn("Error closing rest server", t);
            }
        }
        // 清空集合
        servers.clear();

        if (logger.isInfoEnabled()) {
            logger.info("Closing rest clients");
        }
        for (ResteasyClient client : clients) {
            try {
                // 关闭客户端
                client.close();
            } catch (Throwable t) {
                logger.warn("Error closing rest client", t);
            }
        }
        clients.clear();
    }

    protected String getContextPath(URL url) {
        int pos = url.getPath().lastIndexOf("/");
        return pos > 0 ? url.getPath().substring(0, pos) : "";
    }

    protected class ConnectionMonitor extends Thread {
        /**
         * 是否关闭
         */
        private volatile boolean shutdown;
        /**
         * 连接池集合
         */
        private final List<PoolingHttpClientConnectionManager> connectionManagers = Collections.synchronizedList(new LinkedList<PoolingHttpClientConnectionManager>());

        public void addConnectionManager(PoolingHttpClientConnectionManager connectionManager) {
            connectionManagers.add(connectionManager);
        }

        @Override
        public void run() {
            try {
                while (!shutdown) {
                    synchronized (this) {
                        wait(1000);
                        for (PoolingHttpClientConnectionManager connectionManager : connectionManagers) {
                            // 关闭池中所有过期的连接
                            connectionManager.closeExpiredConnections();
                            // TODO constant
                            // 关闭池中的空闲连接
                            connectionManager.closeIdleConnections(30, TimeUnit.SECONDS);
                        }
                    }
                }
            } catch (InterruptedException ex) {
                // 关闭
                shutdown();
            }
        }

        public void shutdown() {
            shutdown = true;
            connectionManagers.clear();
            synchronized (this) {
                notifyAll();
            }
        }
    }
}
