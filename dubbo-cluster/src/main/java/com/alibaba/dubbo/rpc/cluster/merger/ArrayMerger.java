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
package com.alibaba.dubbo.rpc.cluster.merger;

import com.alibaba.dubbo.rpc.cluster.Merger;

import java.lang.reflect.Array;

public class ArrayMerger implements Merger<Object[]> {

    /**
     * 单例
     */
    public static final ArrayMerger INSTANCE = new ArrayMerger();

    @Override
    public Object[] merge(Object[]... others) {
        // 如果长度为0  则直接返回
        if (others.length == 0) {
            return null;
        }
        // 总长
        int totalLen = 0;
        // 遍历所有需要合并的对象
        for (int i = 0; i < others.length; i++) {
            Object item = others[i];
            // 如果为数组
            if (item != null && item.getClass().isArray()) {
                // 累加数组长度
                totalLen += Array.getLength(item);
            } else {
                throw new IllegalArgumentException((i + 1) + "th argument is not an array");
            }
        }

        if (totalLen == 0) {
            return null;
        }

        // 获得数组类型
        Class<?> type = others[0].getClass().getComponentType();

        // 创建长度
        Object result = Array.newInstance(type, totalLen);
        int index = 0;
        // 遍历需要合并的对象
        for (Object array : others) {
            // 遍历每个数组中的数据
            for (int i = 0; i < Array.getLength(array); i++) {
                // 加入到最终结果中
                Array.set(result, index++, Array.get(array, i));
            }
        }
        return (Object[]) result;
    }

}
