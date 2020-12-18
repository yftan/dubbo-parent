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
package com.alibaba.dubbo.rpc.protocol.injvm;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;

import java.util.Map;

/**
 * InjvmInvoker
 */
class InjvmInvoker<T> extends AbstractInvoker<T> {

    /**
     * 服务key
     */
    private final String key;

    /**
     * 暴露者集合
     */
    private final Map<String, Exporter<?>> exporterMap;

    InjvmInvoker(Class<T> type, URL url, String key, Map<String, Exporter<?>> exporterMap) {
        super(type, url);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    /**
     * 服务是否活跃
     * @return
     */
    @Override
    public boolean isAvailable() {
        InjvmExporter<?> exporter = (InjvmExporter<?>) exporterMap.get(key);
        if (exporter == null) {
            return false;
        } else {
            return super.isAvailable();
        }
    }

    /**
     * invoke方法
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    public Result doInvoke(Invocation invocation) throws Throwable {
        // 获得暴露者
        Exporter<?> exporter = InjvmProtocol.getExporter(exporterMap, getUrl());
        // 如果为空，则抛出异常
        if (exporter == null) {
            throw new RpcException("Service [" + key + "] not found.");
        }
        // 设置远程地址为127.0.0.1
        RpcContext.getContext().setRemoteAddress(NetUtils.LOCALHOST, 0);
        // 调用下一个调用链
        return exporter.getInvoker().invoke(invocation);
    }
}
