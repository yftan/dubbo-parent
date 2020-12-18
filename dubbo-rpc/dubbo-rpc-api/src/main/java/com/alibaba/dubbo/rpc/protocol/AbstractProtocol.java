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
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * abstract ProtocolSupport.
 */
public abstract class AbstractProtocol implements Protocol {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 服务暴露者集合
     */
    protected final Map<String, Exporter<?>> exporterMap = new ConcurrentHashMap<String, Exporter<?>>();

    /**
     * 服务引用者集合
     */
    //TODO SOFEREFENCE
    protected final Set<Invoker<?>> invokers = new ConcurrentHashSet<Invoker<?>>();

    protected static String serviceKey(URL url) {
        // 获得绑定的端口号
        int port = url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
        return serviceKey(port, url.getPath(), url.getParameter(Constants.VERSION_KEY),
                url.getParameter(Constants.GROUP_KEY));
    }

    /**
     *  得到服务key  group+"/"+serviceName+":"+serviceVersion+":"+port
     * @param port
     * @param serviceName
     * @param serviceVersion
     * @param serviceGroup
     * @return
     */
    protected static String serviceKey(int port, String serviceName, String serviceVersion, String serviceGroup) {
        return ProtocolUtils.serviceKey(port, serviceName, serviceVersion, serviceGroup);
    }

    @Override
    public void destroy() {
        // 遍历服务引用实体
        for (Invoker<?> invoker : invokers) {
            if (invoker != null) {
                // 从集合中移除
                invokers.remove(invoker);
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroy reference: " + invoker.getUrl());
                    }
                    // 销毁
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        // 遍历服务暴露者
        for (String key : new ArrayList<String>(exporterMap.keySet())) {
            // 从集合中移除
            Exporter<?> exporter = exporterMap.remove(key);
            if (exporter != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Unexport service: " + exporter.getInvoker().getUrl());
                    }
                    // 取消暴露
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }
}
