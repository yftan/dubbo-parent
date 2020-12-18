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
package com.alibaba.dubbo.remoting.http.jetty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.servlet.DispatcherServlet;
import com.alibaba.dubbo.remoting.http.servlet.ServletManager;
import com.alibaba.dubbo.remoting.http.support.AbstractHttpServer;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.log.Log;
import org.mortbay.log.StdErrLog;
import org.mortbay.thread.QueuedThreadPool;

public class JettyHttpServer extends AbstractHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(JettyHttpServer.class);

    /**
     * 内嵌的Jetty服务器对象
     */
    private Server server;

    /**
     * url对象
     */
    private URL url;

    public JettyHttpServer(URL url, final HttpHandler handler) {
        super(url, handler);
        this.url = url;
        // TODO we should leave this setting to slf4j
        // we must disable the debug logging for production use
        // 设置日志
        Log.setLog(new StdErrLog());
        // 禁用调试用的日志
        Log.getLog().setDebugEnabled(false);

        // 添加http服务器处理器
        DispatcherServlet.addHttpHandler(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()), handler);

        // 获得线程数
        int threads = url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS);
        // 创建线程池
        QueuedThreadPool threadPool = new QueuedThreadPool();
        // 设置线程池配置
        threadPool.setDaemon(true);
        threadPool.setMaxThreads(threads);
        threadPool.setMinThreads(threads);

        // 创建选择NIO连接器
        SelectChannelConnector connector = new SelectChannelConnector();

        // 获得绑定的ip
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        if (!url.isAnyHost() && NetUtils.isValidLocalHost(bindIp)) {
            // 设置主机地址
            connector.setHost(bindIp);
        }
        // 设置端口号
        connector.setPort(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()));

        // 创建Jetty服务器对象
        server = new Server();
        // 设置线程池
        server.setThreadPool(threadPool);
        // 设置连接器
        server.addConnector(connector);

        // 添加DispatcherServlet到jetty
        ServletHandler servletHandler = new ServletHandler();
        ServletHolder servletHolder = servletHandler.addServletWithMapping(DispatcherServlet.class, "/*");
        servletHolder.setInitOrder(2);

        // dubbo's original impl can't support the use of ServletContext
//        server.addHandler(servletHandler);
        // TODO Context.SESSIONS is the best option here?
        Context context = new Context(server, "/", Context.SESSIONS);
        context.setServletHandler(servletHandler);
        // 添加 ServletContext 对象，到 ServletManager 中
        ServletManager.getInstance().addServletContext(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()), context.getServletContext());

        try {
            // 启动jetty服务器
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start jetty server on " + url.getParameter(Constants.BIND_IP_KEY) + ":" + url.getParameter(Constants.BIND_PORT_KEY) + ", cause: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        super.close();

        // 移除 ServletContext 对象
        ServletManager.getInstance().removeServletContext(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()));

        if (server != null) {
            try {
                // 停止服务器
                server.stop();
            } catch (Exception e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }

}
