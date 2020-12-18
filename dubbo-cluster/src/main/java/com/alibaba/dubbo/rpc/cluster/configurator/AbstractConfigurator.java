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
package com.alibaba.dubbo.rpc.cluster.configurator;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AbstractOverrideConfigurator
 *
 */
public abstract class AbstractConfigurator implements Configurator {

    /**
     * url对象
     */
    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    public static void main(String[] args) {
        System.out.println(URL.encode("timeout=100"));
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    @Override
    public URL configure(URL url) {
        if (configuratorUrl == null || configuratorUrl.getHost() == null
                || url == null || url.getHost() == null) {
            return url;
        }
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        // 如果覆盖url具有端口，则表示它是提供者地址。我们希望使用此覆盖URL控制特定提供程序，它可以在提供端生效 也可以在消费端生效。
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {// override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0
            // 1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore;
            // 2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider
            // 配置规则，URL 没有端口，意味着override 输入消费端地址 或者 0.0.0.0
            if (url.getParameter(Constants.SIDE_KEY, Constants.PROVIDER).equals(Constants.CONSUMER)) {
                // 如果它是一个消费者ip地址，目的是控制一个特定的消费者实例，它必须在消费者一方生效，任何提供者收到这个覆盖url应该忽略;
                return configureIfMatch(NetUtils.getLocalHost(), url);// NetUtils.getLocalHost is the ip address consumer registered to registry.
            } else if (url.getParameter(Constants.SIDE_KEY, Constants.CONSUMER).equals(Constants.PROVIDER)) {
                // 如果ip为0.0.0.0，则此覆盖url可以在使用者上使用，也可以在提供者上使用
                return configureIfMatch(Constants.ANYHOST_VALUE, url);// take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
            }
        }
        return url;
    }

    /**
     * 配置规则匹配
     * @param host
     * @param url
     * @return
     */
    private URL configureIfMatch(String host, URL url) {
        // 匹配 Host
        if (Constants.ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            String configApplication = configuratorUrl.getParameter(Constants.APPLICATION_KEY,
                    configuratorUrl.getUsername());
            String currentApplication = url.getParameter(Constants.APPLICATION_KEY, url.getUsername());
            // 匹配 "application"
            if (configApplication == null || Constants.ANY_VALUE.equals(configApplication)
                    || configApplication.equals(currentApplication)) {
                Set<String> conditionKeys = new HashSet<String>();
                // 配置 URL 中的条件 KEYS 集合。其中下面四个 KEY ，不算是条件，而是内置属性。考虑到下面要移除，所以添加到该集合中。
                conditionKeys.add(Constants.CATEGORY_KEY);
                conditionKeys.add(Constants.CHECK_KEY);
                conditionKeys.add(Constants.DYNAMIC_KEY);
                conditionKeys.add(Constants.ENABLED_KEY);
                // 判断传入的 url 是否匹配配置规则 URL 的条件。
                for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    // 除了 "application" 和 "side" 之外，带有 `"~"` 开头的 KEY ，也是条件。
                    if (key.startsWith("~") || Constants.APPLICATION_KEY.equals(key) || Constants.SIDE_KEY.equals(key)) {
                        // 添加搭配条件集合
                        conditionKeys.add(key);
                        if (value != null && !Constants.ANY_VALUE.equals(value)
                                && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                            return url;
                        }
                    }
                }
                // 移除条件 KEYS 集合，并配置到 URL 中
                return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
            }
        }
        return url;
    }

    /**
     * Sort by host, priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        // // host 升序
        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());
        // 如果host相同，则根据priority降序来对比
        if (ipCompare == 0) {//host is the same, sort by priority
            int i = getUrl().getParameter(Constants.PRIORITY_KEY, 0),
                    j = o.getUrl().getParameter(Constants.PRIORITY_KEY, 0);
            return i < j ? -1 : (i == j ? 0 : 1);
        } else {
            return ipCompare;
        }


    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
