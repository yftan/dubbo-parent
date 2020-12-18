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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.util.Map;

/**
 * InjvmProtocol
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol {

    /**
     * 本地调用  Protocol的实现类key
     */
    public static final String NAME = Constants.LOCAL_PROTOCOL;

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 0;
    /**
     * 单例
     */
    private static InjvmProtocol INSTANCE;

    public InjvmProtocol() {
        INSTANCE = this;
    }

    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) {
            // 加载实现
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    /**
     * 获得暴露者
     * @param map
     * @param key
     * @return
     */
    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;

        // 如果服务key不是*
        if (!key.getServiceKey().contains("*")) {
            // 直接从集合中取出
            result = map.get(key.getServiceKey());
        } else {
            // 如果 map不为空，则遍历暴露者，来找到对应的exporter
            if (map != null && !map.isEmpty()) {
                for (Exporter<?> exporter : map.values()) {
                    // 如果是服务key
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {
                        // 赋值
                        result = exporter;
                        break;
                    }
                }
            }
        }

        // 如果没有找到exporter
        if (result == null) {
            // 则返回null
            return null;
        } else if (ProtocolUtils.isGeneric(
                result.getInvoker().getUrl().getParameter(Constants.GENERIC_KEY))) {
            // 如果是泛化调用，则返回null
            return null;
        } else {
            return result;
        }
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 创建InjvmExporter 并且返回
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        // 创建InjvmInvoker 并且返回
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    public boolean isInjvmRefer(URL url) {
        final boolean isJvmRefer;
        // 获得scope配置
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
        if (Constants.LOCAL_PROTOCOL.toString().equals(url.getProtocol())) {
            // 如果是injvm，则不是本地调用
            isJvmRefer = false;
        } else if (Constants.SCOPE_LOCAL.equals(scope) || (url.getParameter("injvm", false))) {
            // if it's declared as local reference
            // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
            // 如果它被声明为本地引用 scope = local'相当于'injvm = true'，将在以后的版本中弃用injvm
            isJvmRefer = true;
        } else if (Constants.SCOPE_REMOTE.equals(scope)) {
            // it's declared as remote reference
            // 如果被声明为远程调用
            isJvmRefer = false;
        } else if (url.getParameter(Constants.GENERIC_KEY, false)) {
            // generic invocation is not local reference
            // 泛化的调用不是本地调用
            isJvmRefer = false;
        } else if (getExporter(exporterMap, url) != null) {
            // by default, go through local reference if there's the service exposed locally
            // 默认情况下，如果本地暴露服务，请通过本地引用
            isJvmRefer = true;
        } else {
            isJvmRefer = false;
        }
        return isJvmRefer;
    }
}
