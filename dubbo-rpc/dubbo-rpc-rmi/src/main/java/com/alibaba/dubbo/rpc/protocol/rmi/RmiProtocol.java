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
package com.alibaba.dubbo.rpc.protocol.rmi;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProxyProtocol;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.rmi.RmiProxyFactoryBean;
import org.springframework.remoting.rmi.RmiServiceExporter;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.rmi.RemoteException;

/**
 * RmiProtocol.
 */
public class RmiProtocol extends AbstractProxyProtocol {

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 1099;

    public RmiProtocol() {
        super(RemoteAccessException.class, RemoteException.class);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 服务暴露
     * @param impl
     * @param type
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // rmi暴露者
        final RmiServiceExporter rmiServiceExporter = new RmiServiceExporter();
        // 设置端口
        rmiServiceExporter.setRegistryPort(url.getPort());
        // 设置服务名称
        rmiServiceExporter.setServiceName(url.getPath());
        // 设置接口
        rmiServiceExporter.setServiceInterface(type);
        // 设置服务实现
        rmiServiceExporter.setService(impl);
        try {
            // 初始化bean的时候执行
            rmiServiceExporter.afterPropertiesSet();
        } catch (RemoteException e) {
            throw new RpcException(e.getMessage(), e);
        }
        return new Runnable() {
            @Override
            public void run() {
                try {
                    // 销毁
                    rmiServiceExporter.destroy();
                } catch (Throwable e) {
                    logger.warn(e.getMessage(), e);
                }
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
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, final URL url) throws RpcException {
        // FactoryBean对于RMI代理，支持传统的RMI服务和RMI调用者，创建RmiProxyFactoryBean对象
        final RmiProxyFactoryBean rmiProxyFactoryBean = new RmiProxyFactoryBean();
        // RMI needs extra parameter since it uses customized remote invocation object
        // 检测版本
        if (url.getParameter(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion()).equals(Version.getProtocolVersion())) {
            // Check dubbo version on provider, this feature only support
            // 设置RemoteInvocationFactory以用于此访问器
            rmiProxyFactoryBean.setRemoteInvocationFactory(new RemoteInvocationFactory() {
                @Override
                public RemoteInvocation createRemoteInvocation(MethodInvocation methodInvocation) {
                    // 自定义调用工厂可以向调用添加更多上下文信息
                    return new RmiRemoteInvocation(methodInvocation);
                }
            });
        }
        // 设置此远程访问者的目标服务的URL。URL必须与特定远程处理提供程序的规则兼容。
        rmiProxyFactoryBean.setServiceUrl(url.toIdentityString());
        // 设置要访问的服务的接口。界面必须适合特定的服务和远程处理策略
        rmiProxyFactoryBean.setServiceInterface(serviceType);
        // 设置是否在找到RMI存根后缓存它
        rmiProxyFactoryBean.setCacheStub(true);
        // 设置是否在启动时查找RMI存根
        rmiProxyFactoryBean.setLookupStubOnStartup(true);
        // 设置是否在连接失败时刷新RMI存根
        rmiProxyFactoryBean.setRefreshStubOnConnectFailure(true);
        // // 初始化bean的时候执行
        rmiProxyFactoryBean.afterPropertiesSet();
        return (T) rmiProxyFactoryBean.getObject();
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null && e.getCause() != null) {
            Class<?> cls = e.getCause().getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                // 抛出超时时间
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                // 抛出网络异常
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                // 抛出序列化异常
                return RpcException.SERIALIZATION_EXCEPTION;
            }
        }
        return super.getErrorCode(e);
    }

}
