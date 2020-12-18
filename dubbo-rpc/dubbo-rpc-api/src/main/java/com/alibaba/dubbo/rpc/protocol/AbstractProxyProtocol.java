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

package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AbstractProxyProtocol
 */
public abstract class AbstractProxyProtocol extends AbstractProtocol {

    /**
     * rpc的异常类集合
     */
    private final List<Class<?>> rpcExceptions = new CopyOnWriteArrayList<Class<?>>();

    /**
     * 代理工厂
     */
    private ProxyFactory proxyFactory;

    public AbstractProxyProtocol() {
    }

    /**
     * 把异常加入到集合
     * @param exceptions
     */
    public AbstractProxyProtocol(Class<?>... exceptions) {
        for (Class<?> exception : exceptions) {
            addRpcException(exception);
        }
    }

    public void addRpcException(Class<?> exception) {
        this.rpcExceptions.add(exception);
    }

    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    /**
     * 暴露服务
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        // 获得uri
        final String uri = serviceKey(invoker.getUrl());
        // 获得服务暴露者
        Exporter<T> exporter = (Exporter<T>) exporterMap.get(uri);
        if (exporter != null) {
            return exporter;
        }
        // 新建一个线程
        final Runnable runnable = doExport(proxyFactory.getProxy(invoker, true), invoker.getInterface(), invoker.getUrl());
        exporter = new AbstractExporter<T>(invoker) {
            /**
             * 取消暴露
             */
            @Override
            public void unexport() {
                super.unexport();
                // 移除该key对应的服务暴露者
                exporterMap.remove(uri);
                if (runnable != null) {
                    try {
                        // 启动线程
                        runnable.run();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            }
        };
        // 加入集合
        exporterMap.put(uri, exporter);
        return exporter;
    }

    /**
     * 引用服务
     * @param type Service class 服务类名
     * @param url  URL address for the remote service
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(final Class<T> type, final URL url) throws RpcException {
        // 通过代理获得实体域
        final Invoker<T> target = proxyFactory.getInvoker(doRefer(type, url), type, url);
        Invoker<T> invoker = new AbstractInvoker<T>(type, url) {
            @Override
            protected Result doInvoke(Invocation invocation) throws Throwable {
                try {
                    // 获得调用结果
                    Result result = target.invoke(invocation);
                    Throwable e = result.getException();
                    // 如果抛出异常，则抛出相应异常
                    if (e != null) {
                        for (Class<?> rpcException : rpcExceptions) {
                            if (rpcException.isAssignableFrom(e.getClass())) {
                                throw getRpcException(type, url, invocation, e);
                            }
                        }
                    }
                    return result;
                } catch (RpcException e) {
                    // 抛出未知异常
                    if (e.getCode() == RpcException.UNKNOWN_EXCEPTION) {
                        e.setCode(getErrorCode(e.getCause()));
                    }
                    throw e;
                } catch (Throwable e) {
                    throw getRpcException(type, url, invocation, e);
                }
            }
        };
        // 加入集合
        invokers.add(invoker);
        return invoker;
    }

    /**
     * 生成rpc异常
     * @param type
     * @param url
     * @param invocation
     * @param e
     * @return
     */
    protected RpcException getRpcException(Class<?> type, URL url, Invocation invocation, Throwable e) {
        RpcException re = new RpcException("Failed to invoke remote service: " + type + ", method: "
                + invocation.getMethodName() + ", cause: " + e.getMessage(), e);
        re.setCode(getErrorCode(e));
        return re;
    }

    /**
     * 返回ip地址
     * @param url
     * @return
     */
    protected String getAddr(URL url) {
        // 获得绑定的ip
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        if (url.getParameter(Constants.ANYHOST_KEY, false)) {
            bindIp = Constants.ANYHOST_VALUE;
        }
        // 返回ip地址
        return NetUtils.getIpByHost(bindIp) + ":" + url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
    }

    /**
     * 返回未知异常的错误码
     * @param e
     * @return
     */
    protected int getErrorCode(Throwable e) {
        return RpcException.UNKNOWN_EXCEPTION;
    }

    protected abstract <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException;

    protected abstract <T> T doRefer(Class<T> type, URL url) throws RpcException;

}
