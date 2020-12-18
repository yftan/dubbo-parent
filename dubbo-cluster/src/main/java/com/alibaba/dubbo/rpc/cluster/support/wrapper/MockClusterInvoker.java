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
package com.alibaba.dubbo.rpc.cluster.support.wrapper;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.support.MockInvoker;

import java.util.List;

public class MockClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(MockClusterInvoker.class);

    /**
     * 目录
     */
    private final Directory<T> directory;

    /**
     * invoker对象
     */
    private final Invoker<T> invoker;

    public MockClusterInvoker(Directory<T> directory, Invoker<T> invoker) {
        this.directory = directory;
        this.invoker = invoker;
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        this.invoker.destroy();
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        Result result = null;

        // 获得 "mock" 配置项，有多种配置方式
        String value = directory.getUrl().getMethodParameter(invocation.getMethodName(), Constants.MOCK_KEY, Boolean.FALSE.toString()).trim();
        // 如果没有mock
        if (value.length() == 0 || value.equalsIgnoreCase("false")) {
            //no mock
            // 直接调用
            result = this.invoker.invoke(invocation);
            // 如果强制服务降级
        } else if (value.startsWith("force")) {
            if (logger.isWarnEnabled()) {
                logger.info("force-mock: " + invocation.getMethodName() + " force-mock enabled , url : " + directory.getUrl());
            }
            //force:direct mock
            // 直接调用 Mock Invoker ，执行本地 Mock 逻辑
            result = doMockInvoke(invocation, null);
        } else {
            //fail-mock
            // 失败服务降级
            try {
                // 否则正常调用
                result = this.invoker.invoke(invocation);
            } catch (RpcException e) {
                if (e.isBiz()) {
                    throw e;
                } else {
                    if (logger.isWarnEnabled()) {
                        logger.warn("fail-mock: " + invocation.getMethodName() + " fail-mock enabled , url : " + directory.getUrl(), e);
                    }
                    // 如果调用失败，则服务降级
                    result = doMockInvoke(invocation, e);
                }
            }
        }
        return result;
    }

    /**
     * 执行本地Mock,服务降级
     * @param invocation
     * @param e
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result doMockInvoke(Invocation invocation, RpcException e) {
        Result result = null;
        Invoker<T> minvoker;

        // 路由匹配 Mock Invoker 集合
        List<Invoker<T>> mockInvokers = selectMockInvoker(invocation);
        // 如果mockInvokers为空，则创建一个MockInvoker
        if (mockInvokers == null || mockInvokers.isEmpty()) {
            // 创建一个MockInvoker
            minvoker = (Invoker<T>) new MockInvoker(directory.getUrl());
        } else {
            // 取出第一个
            minvoker = mockInvokers.get(0);
        }
        try {
            // 调用invoke
            result = minvoker.invoke(invocation);
        } catch (RpcException me) {
            // 如果抛出异常，则返回异常结果
            if (me.isBiz()) {
                result = new RpcResult(me.getCause());
            } else {
                throw new RpcException(me.getCode(), getMockExceptionMessage(e, me), me.getCause());
            }
        } catch (Throwable me) {
            throw new RpcException(getMockExceptionMessage(e, me), me.getCause());
        }
        return result;
    }

    private String getMockExceptionMessage(Throwable t, Throwable mt) {
        String msg = "mock error : " + mt.getMessage();
        if (t != null) {
            msg = msg + ", invoke error is :" + StringUtils.toString(t);
        }
        return msg;
    }

    /**
     * Return MockInvoker
     * Contract：
     * directory.list() will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
     * if directory.list() returns more than one mock invoker, only one of them will be used.
     * 如果调用中存在Constants.INVOCATION_NEED_MOCK，则directory.list（）将返回正常调用者列表，否则，将返回模拟调用者列表。
     * 如果directory.list（）返回多个模拟调用者，则只使用其中一个。
     * 路由匹配 Mock Invoker 集合
     * @param invocation
     * @return
     */
    private List<Invoker<T>> selectMockInvoker(Invocation invocation) {
        List<Invoker<T>> invokers = null;
        //TODO generic invoker？
        if (invocation instanceof RpcInvocation) {
            //Note the implicit contract (although the description is added to the interface declaration, but extensibility is a problem. The practice placed in the attachement needs to be improved)
            // 注意隐式契约（尽管描述被添加到接口声明中，但是可扩展性是一个问题。附件中的做法需要改进）
            ((RpcInvocation) invocation).setAttachment(Constants.INVOCATION_NEED_MOCK, Boolean.TRUE.toString());
            //directory will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
            // 如果调用中存在Constants.INVOCATION_NEED_MOCK，则目录将返回正常调用者列表，否则，将返回模拟调用者列表。
            try {
                invokers = directory.list(invocation);
            } catch (RpcException e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Exception when try to invoke mock. Get mock invokers error for service:"
                            + directory.getUrl().getServiceInterface() + ", method:" + invocation.getMethodName()
                            + ", will contruct a new mock with 'new MockInvoker()'.", e);
                }
            }
        }
        return invokers;
    }

    @Override
    public String toString() {
        return "invoker :" + this.invoker + ",directory: " + this.directory;
    }
}
