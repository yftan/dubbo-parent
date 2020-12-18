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
package com.alibaba.dubbo.remoting.http.tomcat;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.servlet.DispatcherServlet;
import com.alibaba.dubbo.remoting.http.servlet.ServletManager;
import com.alibaba.dubbo.remoting.http.support.AbstractHttpServer;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

public class TomcatHttpServer extends AbstractHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(TomcatHttpServer.class);

    /**
     * 内嵌的tomcat对象
     */
    private final Tomcat tomcat;

    /**
     * url对象
     */
    private final URL url;

    public TomcatHttpServer(URL url, final HttpHandler handler) {
        super(url, handler);

        this.url = url;
        // 添加处理器
        DispatcherServlet.addHttpHandler(url.getPort(), handler);
        // 获得java.io.tmpdir的绝对路径目录
        String baseDir = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        // 创建内嵌的tomcat对象
        tomcat = new Tomcat();
        // 设置根目录
        tomcat.setBaseDir(baseDir);
        // 设置端口号
        tomcat.setPort(url.getPort());
        // 给默认的http连接器。设置最大线程数
        tomcat.getConnector().setProperty(
                "maxThreads", String.valueOf(url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS)));
//        tomcat.getConnector().setProperty(
//                "minSpareThreads", String.valueOf(url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS)));

        // 设置最大的连接数
        tomcat.getConnector().setProperty(
                "maxConnections", String.valueOf(url.getParameter(Constants.ACCEPTS_KEY, -1)));

        // 设置URL编码格式
        tomcat.getConnector().setProperty("URIEncoding", "UTF-8");
        // 设置连接超时事件为60s
        tomcat.getConnector().setProperty("connectionTimeout", "60000");

        // 设置最大长连接个数为不限制个数
        tomcat.getConnector().setProperty("maxKeepAliveRequests", "-1");
        // 设置将由连接器使用的Coyote协议。
        tomcat.getConnector().setProtocol("org.apache.coyote.http11.Http11NioProtocol");

        // 添加上下文
        Context context = tomcat.addContext("/", baseDir);
        // 添加servlet，把servlet添加到context
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet());
        // 添加servlet映射
        context.addServletMapping("/*", "dispatcher");
        // 添加servlet上下文
        ServletManager.getInstance().addServletContext(url.getPort(), context.getServletContext());

        try {
            // 开启tomcat
            tomcat.start();
        } catch (LifecycleException e) {
            throw new IllegalStateException("Failed to start tomcat server at " + url.getAddress(), e);
        }
    }

    @Override
    public void close() {
        super.close();

        // 移除相关的servlet上下文
        ServletManager.getInstance().removeServletContext(url.getPort());

        try {
            // 停止tomcat
            tomcat.stop();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }
}
