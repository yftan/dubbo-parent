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
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class ThriftInvoker<T> extends AbstractInvoker<T> {

    /**
     * 客户端集合
     */
    private final ExchangeClient[] clients;

    /**
     * 活跃的客户端索引
     */
    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    /**
     * 销毁锁
     */
    private final ReentrantLock destroyLock = new ReentrantLock();

    /**
     * invoker集合
     */
    private final Set<Invoker<?>> invokers;

    public ThriftInvoker(Class<T> service, URL url, ExchangeClient[] clients) {
        this(service, url, clients, null);
    }

    public ThriftInvoker(Class<T> type, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(type, url,
                new String[]{Constants.INTERFACE_KEY, Constants.GROUP_KEY,
                        Constants.TOKEN_KEY, Constants.TIMEOUT_KEY});
        this.clients = clients;
        this.invokers = invokers;
    }

    @Override
    protected Result doInvoke(Invocation invocation) throws Throwable {

        RpcInvocation inv = (RpcInvocation) invocation;

        final String methodName;

        // 获得方法名
        methodName = invocation.getMethodName();

        // 设置附加值 path
        inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());

        // for thrift codec
        inv.setAttachment(ThriftCodec.PARAMETER_CLASS_NAME_GENERATOR, getUrl().getParameter(
                ThriftCodec.PARAMETER_CLASS_NAME_GENERATOR, DubboClassNameGenerator.NAME));

        ExchangeClient currentClient;

        // 如果只有一个连接的客户端，则直接返回
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            // 否则，取出下一个客户端，循环数组取
            currentClient = clients[index.getAndIncrement() % clients.length];
        }

        try {
            // 获得超时时间
            int timeout = getUrl().getMethodParameter(
                    methodName, Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);

            RpcContext.getContext().setFuture(null);

            // 发起请求
            return (Result) currentClient.request(inv, timeout).get();

        } catch (TimeoutException e) {
            // 抛出超时异常
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, e.getMessage(), e);
        } catch (RemotingException e) {
            // 抛出网络异常
            throw new RpcException(RpcException.NETWORK_EXCEPTION, e.getMessage(), e);
        }

    }

    /**
     * 检测是否可用
     * @return
     */
    @Override
    public boolean isAvailable() {

        if (!super.isAvailable()) {
            return false;
        }

        for (ExchangeClient client : clients) {
            // 只要有一个客户端连接，就是服务可用
            if (client.isConnected()
                    && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            destroyLock.lock();

            try {

                if (super.isDestroyed()) {
                    return;
                }

                super.destroy();

                if (invokers != null) {
                    invokers.remove(this);
                }

                for (ExchangeClient client : clients) {

                    try {
                        client.close();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }

                }

            } finally {
                destroyLock.unlock();
            }

        }

    }

}
