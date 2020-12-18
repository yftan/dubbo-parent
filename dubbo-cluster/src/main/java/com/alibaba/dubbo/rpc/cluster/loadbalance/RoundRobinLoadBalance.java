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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 * 
 * Smoothly round robin's implementation @since 2.6.5 
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";

    /**
     * 回收间隔
     */
    private static int RECYCLE_PERIOD = 60000;

    /**
     * 加权轮询记录器
     */
    protected static class WeightedRoundRobin {
        /**
         * 权重
         */
        private int weight;
        /**
         * 当前已经有多少请求落在该服务提供者身上，也可以看成是一个动态的权重
         */
        private AtomicLong current = new AtomicLong(0);
        /**
         * 最后一次更新时间
         */
        private long lastUpdate;
        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    /**
     * 方法权重集合
     * 第一个key为方法名，例如com.xxx.DemoService.sayHello
     * 第二个key为identify的值
     */
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();
    /**
     * 更新锁
     */
    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }
    
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // key = 全限定类名 + "." + 方法名，比如 com.xxx.DemoService.sayHello
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }
        // 权重总和
        int totalWeight = 0;
        // 最小权重
        long maxCurrent = Long.MIN_VALUE;
        // 获得现在的时间戳
        long now = System.currentTimeMillis();
        // 创建已经选择的invoker
        Invoker<T> selectedInvoker = null;
        // 创建加权轮询器
        WeightedRoundRobin selectedWRR = null;

        // 下面这个循环主要做了这样几件事情：
        //   1. 遍历 Invoker 列表，检测当前 Invoker 是否有
        //      相应的 WeightedRoundRobin，没有则创建
        //   2. 检测 Invoker 权重是否发生了变化，若变化了，
        //      则更新 WeightedRoundRobin 的 weight 字段
        //   3. 让 current 字段加上自身权重，等价于 current += weight
        //   4. 设置 lastUpdate 字段，即 lastUpdate = now
        //   5. 寻找具有最大 current 的 Invoker，以及 Invoker 对应的 WeightedRoundRobin，
        //      暂存起来，留作后用
        //   6. 计算权重总和
        for (Invoker<T> invoker : invokers) {
            // 获得identify的值
            String identifyString = invoker.getUrl().toIdentityString();
            // 获得加权轮询器
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            // 计算权重
            int weight = getWeight(invoker, invocation);
            // 如果权重小于0，则设置0
            if (weight < 0) {
                weight = 0;
            }
            // 如果加权轮询器为空
            if (weightedRoundRobin == null) {
                // 创建加权轮询器
                weightedRoundRobin = new WeightedRoundRobin();
                // 设置权重
                weightedRoundRobin.setWeight(weight);
                // 加入集合
                map.putIfAbsent(identifyString, weightedRoundRobin);
                weightedRoundRobin = map.get(identifyString);
            }
            // 如果权重跟之前的权重不一样，则重新设置权重
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            // 计数器加1
            long cur = weightedRoundRobin.increaseCurrent();
            // 更新最后一次更新时间
            weightedRoundRobin.setLastUpdate(now);
            // 当落在该服务提供者的统计数大于最大可承受的数
            if (cur > maxCurrent) {
                // 赋值
                maxCurrent = cur;
                // 被选择的selectedInvoker赋值
                selectedInvoker = invoker;
                // 被选择的加权轮询器赋值
                selectedWRR = weightedRoundRobin;
            }
            // 累加
            totalWeight += weight;
        }
        // 如果更新锁不能获得并且invokers的大小跟map大小不匹配
        // 对 <identifyString, WeightedRoundRobin> 进行检查，过滤掉长时间未被更新的节点。
        // 该节点可能挂了，invokers 中不包含该节点，所以该节点的 lastUpdate 长时间无法被更新。
        // 若未更新时长超过阈值后，就会被移除掉，默认阈值为60秒。
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<String, WeightedRoundRobin>();
                    // 复制
                    newMap.putAll(map);
                    Iterator<Entry<String, WeightedRoundRobin>> it = newMap.entrySet().iterator();
                    // 轮询
                    while (it.hasNext()) {
                        Entry<String, WeightedRoundRobin> item = it.next();
                        // 如果大于回收时间，则进行回收
                        if (now - item.getValue().getLastUpdate() > RECYCLE_PERIOD) {
                            // 从集合中移除
                            it.remove();
                        }
                    }
                    // 加入集合
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        // 如果被选择的selectedInvoker不为空
        if (selectedInvoker != null) {
            // 设置总的权重
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
