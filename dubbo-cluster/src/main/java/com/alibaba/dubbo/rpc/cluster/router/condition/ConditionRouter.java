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
package com.alibaba.dubbo.rpc.cluster.router.condition;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter
 *
 */
public class ConditionRouter implements Router, Comparable<Router> {

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
    /**
     * 分组正则匹配
     */
    private static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    /**
     * 路由规则 URL
     */
    private final URL url;
    /**
     * 路由规则的优先级，用于排序，优先级越大越靠前执行，可不填，缺省为 0
     */
    private final int priority;
    /**
     * 当路由结果为空时，是否强制执行，如果不强制执行，路由结果为空的路由规则将自动失效，可不填，缺省为 false 。
     */
    private final boolean force;
    /**
     * 消费者匹配条件集合，通过解析【条件表达式 rule 的 `=>` 之前半部分】
     */
    private final Map<String, MatchPair> whenCondition;
    /**
     * 提供者地址列表的过滤条件，通过解析【条件表达式 rule 的 `=>` 之后半部分】
     */
    private final Map<String, MatchPair> thenCondition;

    public ConditionRouter(URL url) {
        this.url = url;
        // 获得优先级配置
        this.priority = url.getParameter(Constants.PRIORITY_KEY, 0);
        // 获得是否强制执行配置
        this.force = url.getParameter(Constants.FORCE_KEY, false);
        try {
            // 获得规则
            String rule = url.getParameterAndDecoded(Constants.RULE_KEY);
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", "");
            int i = rule.indexOf("=>");
            // 分割消费者和提供者规则
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 解析路由配置内容
     * @param rule
     * @return
     * @throws ParseException
     */
    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        // 如果规则为空，则直接返回空
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;
        // 正则表达式匹配
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        // 一个一个匹配
        while (matcher.find()) { // Try to match one by one
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // Start part of the condition expression.
            // 开始条件表达式
            if (separator == null || separator.length() == 0) {
                pair = new MatchPair();
                // 保存条件
                condition.put(content, pair);
            }
            // The KV part of the condition expression
            else if ("&".equals(separator)) {
                // 把参数的条件表达式放入condition
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }
            // The Value in the KV part.
            // 把值放入values
            else if ("=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.matches;
                values.add(content);
            }
            // The Value in the KV part.
            // 把不等于的条件限制也放入values
            else if ("!=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.mismatches;
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            // 如果以.分隔的也放入values
            else if (",".equals(separator)) { // Should be seperateed by ','
                if (values == null || values.isEmpty())
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        // 为空，直接返回空 Invoker 集合
        if (invokers == null || invokers.isEmpty()) {
            return invokers;
        }
        try {
            // 如果不匹配 `whenCondition` ，直接返回 `invokers` 集合，因为不需要走 `whenThen` 的匹配
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            // 如果thenCondition为空，则直接返回空
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            // 遍历invokers
            for (Invoker<T> invoker : invokers) {
                // 如果thenCondition匹配，则加入result
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        return invokers;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public int compareTo(Router o) {
        if (o == null || o.getClass() != ConditionRouter.class) {
            return 1;
        }
        ConditionRouter c = (ConditionRouter) o;
        return this.priority == c.priority ? url.toFullString().compareTo(c.url.toFullString()) : (this.priority > c.priority ? 1 : -1);
    }

    boolean matchWhen(URL url, Invocation invocation) {
        return whenCondition == null || whenCondition.isEmpty() || matchCondition(whenCondition, url, null, invocation);
    }

    private boolean matchThen(URL url, URL param) {
        return !(thenCondition == null || thenCondition.isEmpty()) && matchCondition(thenCondition, url, param, null);
    }

    /**
     * 匹配条件
     * @param condition
     * @param url
     * @param param
     * @param invocation
     * @return
     */
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        Map<String, String> sample = url.toMap();
        // 是否匹配
        boolean result = false;
        // 遍历条件
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();
            String sampleValue;
            //get real invoked method name from invocation
            // 获得方法名
            if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            } else {
                //
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
                }
            }
            if (sampleValue != null) {
                // 如果不匹配条件值，返回false
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    // 匹配则返回true
                    result = true;
                }
            } else {
                //not pass the condition
                // 如果匹配的集合不为空
                if (!matchPair.getValue().matches.isEmpty()) {
                    // 返回false
                    return false;
                } else {
                    // 返回true
                    result = true;
                }
            }
        }
        return result;
    }

    /**
     * 封装了匹配的值，每个属性条件
     */
    private static final class MatchPair {
        /**
         * 匹配的值的集合
         */
        final Set<String> matches = new HashSet<String>();
        /**
         * 不匹配的值的集合
         */
        final Set<String> mismatches = new HashSet<String>();

        /**
         * 判断value是否匹配matches或者mismatches
         * @param value
         * @param param
         * @return
         */
        private boolean isMatch(String value, URL param) {
            // 只匹配 matches
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        // 匹配上了返回true
                        return true;
                    }
                }
                // 没匹配上则为false
                return false;
            }
            // 只匹配 mismatches
            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        // 如果匹配上了，则返回false
                        return false;
                    }
                }
                // 没匹配上，则为true
                return true;
            }
            // 匹配 matches和mismatches
            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                //when both mismatches and matches contain the same value, then using mismatches first
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        // 匹配上则为false
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        // 匹配上则为true
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }
}
