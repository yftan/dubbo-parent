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
package com.alibaba.dubbo.rpc.protocol.http;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.http.HttpBinder;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.HttpServer;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProxyProtocol;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.httpinvoker.HttpComponentsHttpInvokerRequestExecutor;
import org.springframework.remoting.httpinvoker.HttpInvokerProxyFactoryBean;
import org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter;
import org.springframework.remoting.httpinvoker.SimpleHttpInvokerRequestExecutor;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HttpProtocol
 */
public class HttpProtocol extends AbstractProxyProtocol {

    /**
     * 默认的端口号
     */
    public static final int DEFAULT_PORT = 80;

    /**
     * http服务器集合
     */
    private final Map<String, HttpServer> serverMap = new ConcurrentHashMap<String, HttpServer>();

    /**
     * Spring HttpInvokerServiceExporter 集合
     */
    private final Map<String, HttpInvokerServiceExporter> skeletonMap = new ConcurrentHashMap<String, HttpInvokerServiceExporter>();

    /**
     * HttpBinder对象
     */
    private HttpBinder httpBinder;

    public HttpProtocol() {
        super(RemoteAccessException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
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
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // 获得ip地址
        String addr = getAddr(url);
        // 获得http服务器
        HttpServer server = serverMap.get(addr);
        // 如果服务器为空，则重新创建服务器，并且加入到集合
        if (server == null) {
            server = httpBinder.bind(url, new InternalHandler());
            serverMap.put(addr, server);
        }

        // 获得服务path
        final String path = url.getAbsolutePath();

        // 加入集合
        skeletonMap.put(path, createExporter(impl, type));

        // 通用path
        final String genericPath = path + "/" + Constants.GENERIC_KEY;

        // 添加泛化的服务调用
        skeletonMap.put(genericPath, createExporter(impl, GenericService.class));
        return new Runnable() {
            @Override
            public void run() {
                skeletonMap.remove(path);
                skeletonMap.remove(genericPath);
            }
        };
    }
    /**
     * 创建一个spring 的HttpInvokerServiceExporter
     * @param impl
     * @param type
     * @param <T>
     * @return
     */
    private <T> HttpInvokerServiceExporter createExporter(T impl, Class<?> type) {
        // 创建HttpInvokerServiceExporter
        final HttpInvokerServiceExporter httpServiceExporter = new HttpInvokerServiceExporter();
        // 设置要访问的服务的接口
        httpServiceExporter.setServiceInterface(type);
        // 设置服务实现
        httpServiceExporter.setService(impl);
        try {
            // 在BeanFactory设置了所有提供的bean属性，初始化bean的时候执行，可以针对某个具体的bean进行配
            httpServiceExporter.afterPropertiesSet();
        } catch (Exception e) {
            throw new RpcException(e.getMessage(), e);
        }
        return httpServiceExporter;
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
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, final URL url) throws RpcException {
        // 获得泛化配置
        final String generic = url.getParameter(Constants.GENERIC_KEY);
        // 是否为泛化调用
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);

        // 创建HttpInvokerProxyFactoryBean
        final HttpInvokerProxyFactoryBean httpProxyFactoryBean = new HttpInvokerProxyFactoryBean();
        // 设置RemoteInvocation的工厂类
        httpProxyFactoryBean.setRemoteInvocationFactory(new RemoteInvocationFactory() {
            /**
             * 为给定的AOP方法调用创建一个新的RemoteInvocation对象。
             * @param methodInvocation
             * @return
             */
            @Override
            public RemoteInvocation createRemoteInvocation(MethodInvocation methodInvocation) {
                // 新建一个HttpRemoteInvocation
                RemoteInvocation invocation = new HttpRemoteInvocation(methodInvocation);
                // 如果是泛化调用
                if (isGeneric) {
                    // 设置标志
                    invocation.addAttribute(Constants.GENERIC_KEY, generic);
                }
                return invocation;
            }
        });
        // 获得identity message
        String key = url.toIdentityString();
        // 如果是泛化调用
        if (isGeneric) {
            key = key + "/" + Constants.GENERIC_KEY;
        }
        // 设置服务url
        httpProxyFactoryBean.setServiceUrl(key);
        // 设置服务接口
        httpProxyFactoryBean.setServiceInterface(serviceType);
        // 获得客户端参数
        String client = url.getParameter(Constants.CLIENT_KEY);
        if (client == null || client.length() == 0 || "simple".equals(client)) {
            // 创建SimpleHttpInvokerRequestExecutor连接池 使用的是JDK HttpClient
            SimpleHttpInvokerRequestExecutor httpInvokerRequestExecutor = new SimpleHttpInvokerRequestExecutor() {
                @Override
                protected void prepareConnection(HttpURLConnection con,
                                                 int contentLength) throws IOException {
                    super.prepareConnection(con, contentLength);
                    // 设置读取超时时间
                    con.setReadTimeout(url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
                    // 设置连接超时时间
                    con.setConnectTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT));
                }
            };
            httpProxyFactoryBean.setHttpInvokerRequestExecutor(httpInvokerRequestExecutor);
        } else if ("commons".equals(client)) {
            // 创建 HttpComponentsHttpInvokerRequestExecutor连接池 使用的是Apache HttpClient
            HttpComponentsHttpInvokerRequestExecutor httpInvokerRequestExecutor = new HttpComponentsHttpInvokerRequestExecutor();
            // 设置读取超时时间
            httpInvokerRequestExecutor.setReadTimeout(url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
            // 设置连接超时时间
            httpInvokerRequestExecutor.setConnectTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT));
            httpProxyFactoryBean.setHttpInvokerRequestExecutor(httpInvokerRequestExecutor);
        } else {
            throw new IllegalStateException("Unsupported http protocol client " + client + ", only supported: simple, commons");
        }
        httpProxyFactoryBean.afterPropertiesSet();
        // 返回HttpInvokerProxyFactoryBean对象
        return (T) httpProxyFactoryBean.getObject();
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                // 返回超时异常
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                // 返回网络异常
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                // 返回序列化异常
                return RpcException.SERIALIZATION_EXCEPTION;
            }
        }
        return super.getErrorCode(e);
    }

    /**
     * 处理器
     */
    private class InternalHandler implements HttpHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            // 获得请求uri
            String uri = request.getRequestURI();
            // 获得服务暴露者HttpInvokerServiceExporter对象
            HttpInvokerServiceExporter skeleton = skeletonMap.get(uri);
            // 如果不是post，则返回码设置500
            if (!request.getMethod().equalsIgnoreCase("POST")) {
                response.setStatus(500);
            } else {
                // 远程地址放到上下文
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    // 调用下一个调用
                    skeleton.handleRequest(request, response);
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            }
        }

    }

}
