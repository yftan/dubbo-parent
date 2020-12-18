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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConsistentHashLoadBalance
 *
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    /**
     * 一致性 hash 选择器集合
     */
    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获得方法名
        String methodName = RpcUtils.getMethodName(invocation);
        // 获得key
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        // 获取 invokers 原始的 hashcode
        int identityHashCode = System.identityHashCode(invokers);
        // 从一致性 hash 选择器集合中获得一致性 hash 选择器
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        // 如果等于空或者选择器的hash值不等于原始的值，则新建一个一致性 hash 选择器，并且加入到集合
        if (selector == null || selector.identityHashCode != identityHashCode) {
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        // 选择器选择一个invoker
        return selector.select(invocation);
    }

    /**
     * 一致性 hash 选择器
     * @param <T>
     */
    private static final class ConsistentHashSelector<T> {

        /**
         * 存储 Invoker 虚拟节点
         */
        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        /**
         * 每个Invoker 对应的虚拟节点数
         */
        private final int replicaNumber;

        /**
         * 原始哈希值
         */
        private final int identityHashCode;

        /**
         * 取值参数位置数组
         */
        private final int[] argumentIndex;

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            // 获取虚拟节点数，默认为160
            this.replicaNumber = url.getMethodParameter(methodName, "hash.nodes", 160);
            // 获取参与 hash 计算的参数下标值，默认对第一个参数进行 hash 运算
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, "hash.arguments", "0"));
            // 创建下标数组
            argumentIndex = new int[index.length];
            // 遍历
            for (int i = 0; i < index.length; i++) {
                // 记录下标
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 遍历invokers
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 对 address + i 进行 md5 运算，得到一个长度为16的字节数组
                    byte[] digest = md5(address + i);
                    // // 对 digest 部分字节进行4次 hash 运算，得到四个不同的 long 型正整数
                    for (int h = 0; h < 4; h++) {
                        // h = 0 时，取 digest 中下标为 0 ~ 3 的4个字节进行位运算
                        // h = 1 时，取 digest 中下标为 4 ~ 7 的4个字节进行位运算
                        // h = 2, h = 3 时过程同上
                        long m = hash(digest, h);
                        // 将 hash 到 invoker 的映射关系存储到 virtualInvokers 中，
                        // virtualInvokers 需要提供高效的查询操作，因此选用 TreeMap 作为存储结构
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        /**
         * 选择一个invoker
         * @param invocation
         * @return
         */
        public Invoker<T> select(Invocation invocation) {
            // 将参数转为 key
            String key = toKey(invocation.getArguments());
            // 对参数 key 进行 md5 运算
            byte[] digest = md5(key);
            // 取 digest 数组的前四个字节进行 hash 运算，再将 hash 值传给 selectForKey 方法，
            // 寻找合适的 Invoker
            return selectForKey(hash(digest, 0));
        }

        /**
         * 将参数转为 key
         * @param args
         * @return
         */
        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            // 遍历参数下标
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    // 拼接参数，生成key
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        /**
         * 通过hash选择invoker
         * @param hash
         * @return
         */
        private Invoker<T> selectForKey(long hash) {
            // 到 TreeMap 中查找第一个节点值大于或等于当前 hash 的 Invoker
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.tailMap(hash, true).firstEntry();
            // 如果 hash 大于 Invoker 在圆环上最大的位置，此时 entry = null，
            // 需要将 TreeMap 的头节点赋值给 entry
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }
            // 返回选择的invoker
            return entry.getValue();
        }

        /**
         * 计算hash值
         * @param digest
         * @param number
         * @return
         */
        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        /**
         * md5
         * @param value
         * @return
         */
        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes;
            try {
                bytes = value.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.update(bytes);
            return md5.digest();
        }

    }

}
