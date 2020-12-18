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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Merger;
import com.alibaba.dubbo.rpc.cluster.merger.MergerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unchecked")
public class MergeableClusterInvoker<T> implements Invoker<T> {

    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);
    /**
     * 目录
     */
    private final Directory<T> directory;
    /**
     * 线程池
     */
    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));

    public MergeableClusterInvoker(Directory<T> directory) {
        this.directory = directory;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Result invoke(final Invocation invocation) throws RpcException {
        // 获得invoker集合
        List<Invoker<T>> invokers = directory.list(invocation);

        /**
         * 获得是否merger
         */
        String merger = getUrl().getMethodParameter(invocation.getMethodName(), Constants.MERGER_KEY);
        // 如果没有设置需要聚合，则只调用一个invoker的
        if (ConfigUtils.isEmpty(merger)) { // If a method doesn't have a merger, only invoke one Group
            // 只要有一个可用就返回
            for (final Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    return invoker.invoke(invocation);
                }
            }
            return invokers.iterator().next().invoke(invocation);
        }

        // 返回类型
        Class<?> returnType;
        try {
            // 获得返回类型
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }

        // 结果集合
        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();
        // 循环invokers
        for (final Invoker<T> invoker : invokers) {
            // 获得每次调用的future
            Future<Result> future = executor.submit(new Callable<Result>() {
                @Override
                public Result call() throws Exception {
                    // 回调，把返回结果放入future
                    return invoker.invoke(new RpcInvocation(invocation, invoker));
                }
            });
            // 加入集合
            results.put(invoker.getUrl().getServiceKey(), future);
        }

        Object result = null;

        List<Result> resultList = new ArrayList<Result>(results.size());

        // 获得超时时间
        int timeout = getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // 遍历每一个结果
        for (Map.Entry<String, Future<Result>> entry : results.entrySet()) {
            Future<Result> future = entry.getValue();
            try {
                // 获得调用返回的结果
                Result r = future.get(timeout, TimeUnit.MILLISECONDS);
                if (r.hasException()) {
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) + 
                                    " failed: " + r.getException().getMessage(), 
                            r.getException());
                } else {
                    // 加入集合
                    resultList.add(r);
                }
            } catch (Exception e) {
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }

        // 如果为空，则返回空的结果
        if (resultList.isEmpty()) {
            return new RpcResult((Object) null);
        } else if (resultList.size() == 1) {
            // 如果只有一个结果，则返回该结果
            return resultList.iterator().next();
        }

        // 如果返回类型是void，也就是没有返回值，那么返回空结果
        if (returnType == void.class) {
            return new RpcResult((Object) null);
        }

        // 根据方法来合并，将调用返回结果的指定方法进行合并
        if (merger.startsWith(".")) {
            merger = merger.substring(1);
            Method method;
            try {
                // 获得方法
                method = returnType.getMethod(merger, returnType);
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " + 
                        returnType.getClass().getName() + " ]");
            }

            // 有 Method ，进行合并
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }
            // 从集合中移除
            result = resultList.remove(0).getValue();
            try {
                // 方法返回类型匹配，合并时，修改 result
                if (method.getReturnType() != void.class
                        && method.getReturnType().isAssignableFrom(result.getClass())) {
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }
                } else {
                    // 方法返回类型不匹配，合并时，不修改 result
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }
        } else {
            // 基于 Merger
            Merger resultMerger;
            // 如果是默认的方式
            if (ConfigUtils.isDefault(merger)) {
                // 获得该类型的合并方式
                resultMerger = MergerFactory.getMerger(returnType);
            } else {
                // 如果不是默认的，则配置中指定获得Merger的实现类
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }
            if (resultMerger != null) {
                List<Object> rets = new ArrayList<Object>(resultList.size());
                // 遍历返回结果
                for (Result r : resultList) {
                    // 加入到rets
                    rets.add(r.getValue());
                }
                // 合并
                result = resultMerger.merge(
                        rets.toArray((Object[]) Array.newInstance(returnType, 0)));
            } else {
                throw new RpcException("There is no merger to merge result.");
            }
        }
        // 返回结果
        return new RpcResult(result);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
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
        directory.destroy();
    }

    private String getGroupDescFromServiceKey(String key) {
        int index = key.indexOf("/");
        if (index > 0) {
            return "group [ " + key.substring(0, index) + " ]";
        }
        return key;
    }
}
