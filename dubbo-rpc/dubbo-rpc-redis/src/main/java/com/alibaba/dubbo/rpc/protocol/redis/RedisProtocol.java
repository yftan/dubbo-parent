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
package com.alibaba.dubbo.rpc.protocol.redis;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;


/**
 * RedisProtocol
 */
public class RedisProtocol extends AbstractProtocol {

    /**
     * 默认端口号
     */
    public static final int DEFAULT_PORT = 6379;

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 服务暴露
     * @param invoker Service invoker 服务的实体域
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        // 不支持redis协议的服务暴露，抛出异常
        throw new UnsupportedOperationException("Unsupported export redis service. url: " + invoker.getUrl());
    }

    /**
     * 获得序列化的实现类
     * @param url
     * @return
     */
    private Serialization getSerialization(URL url) {
        return ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(url.getParameter(Constants.SERIALIZATION_KEY, "java"));
    }

    /**
     * 服务引用
     * @param type Service class 服务类名
     * @param url  URL address for the remote service
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(final Class<T> type, final URL url) throws RpcException {
        try {
            // 实例化对象池
            GenericObjectPoolConfig config = new GenericObjectPoolConfig();
            // 如果 testOnBorrow 被设置，pool 会在 borrowObject 返回对象之前使用 PoolableObjectFactory的 validateObject 来验证这个对象是否有效
            // 要是对象没通过验证，这个对象会被丢弃，然后重新选择一个新的对象。
            config.setTestOnBorrow(url.getParameter("test.on.borrow", true));
            // 如果 testOnReturn 被设置， pool 会在 returnObject 的时候通过 PoolableObjectFactory 的validateObject 方法验证对象
            // 如果对象没通过验证，对象会被丢弃，不会被放到池中。
            config.setTestOnReturn(url.getParameter("test.on.return", false));
            // 指定空闲对象是否应该使用 PoolableObjectFactory 的 validateObject 校验，如果校验失败，这个对象会从对象池中被清除。
            // 这个设置仅在 timeBetweenEvictionRunsMillis 被设置成正值（ >0） 的时候才会生效。
            config.setTestWhileIdle(url.getParameter("test.while.idle", false));
            if (url.getParameter("max.idle", 0) > 0)
                // 控制一个pool最多有多少个状态为空闲的jedis实例。
                config.setMaxIdle(url.getParameter("max.idle", 0));
            if (url.getParameter("min.idle", 0) > 0)
                // 控制一个pool最少有多少个状态为空闲的jedis实例。
                config.setMinIdle(url.getParameter("min.idle", 0));
            if (url.getParameter("max.active", 0) > 0)
                // 控制一个pool最多有多少个jedis实例。
                config.setMaxTotal(url.getParameter("max.active", 0));
            if (url.getParameter("max.total", 0) > 0)
                config.setMaxTotal(url.getParameter("max.total", 0));
            if (url.getParameter("max.wait", 0) > 0)
                //表示当引入一个jedis实例时，最大的等待时间，如果超过等待时间，则直接抛出JedisConnectionException；
                config.setMaxWaitMillis(url.getParameter("max.wait", 0));
            if (url.getParameter("num.tests.per.eviction.run", 0) > 0)
                // 设置驱逐线程每次检测对象的数量。这个设置仅在 timeBetweenEvictionRunsMillis 被设置成正值（ >0）的时候才会生效。
                config.setNumTestsPerEvictionRun(url.getParameter("num.tests.per.eviction.run", 0));
            if (url.getParameter("time.between.eviction.runs.millis", 0) > 0)
                // 指定驱逐线程的休眠时间。如果这个值不是正数（ >0），不会有驱逐线程运行。
                config.setTimeBetweenEvictionRunsMillis(url.getParameter("time.between.eviction.runs.millis", 0));
            if (url.getParameter("min.evictable.idle.time.millis", 0) > 0)
                // 指定最小的空闲驱逐的时间间隔（空闲超过指定的时间的对象，会被清除掉）。
                // 这个设置仅在 timeBetweenEvictionRunsMillis 被设置成正值（ >0）的时候才会生效。
                config.setMinEvictableIdleTimeMillis(url.getParameter("min.evictable.idle.time.millis", 0));
            // 创建redis连接池
            final JedisPool jedisPool = new JedisPool(config, url.getHost(), url.getPort(DEFAULT_PORT),
                    url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT),
                    StringUtils.isBlank(url.getPassword()) ? null : url.getPassword(),
                    url.getParameter("db.index", 0));
            // 获得值的过期时间
            final int expiry = url.getParameter("expiry", 0);
            // 获得get命令
            final String get = url.getParameter("get", "get");
            // 获得set命令
            final String set = url.getParameter("set", Map.class.equals(type) ? "put" : "set");
            // 获得delete命令
            final String delete = url.getParameter("delete", Map.class.equals(type) ? "remove" : "delete");
            return new AbstractInvoker<T>(type, url) {
                @Override
                protected Result doInvoke(Invocation invocation) throws Throwable {
                    Jedis resource = null;
                    try {
                        resource = jedisPool.getResource();

                        // 如果是get命令
                        if (get.equals(invocation.getMethodName())) {
                            // get 命令必须只有一个参数
                            if (invocation.getArguments().length != 1) {
                                throw new IllegalArgumentException("The redis get method arguments mismatch, must only one arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            // 获得值
                            byte[] value = resource.get(String.valueOf(invocation.getArguments()[0]).getBytes());
                            if (value == null) {
                                return new RpcResult();
                            }
                            // 反序列化
                            ObjectInput oin = getSerialization(url).deserialize(url, new ByteArrayInputStream(value));
                            return new RpcResult(oin.readObject());
                        } else if (set.equals(invocation.getMethodName())) {
                            // 如果是set命令，参数长度必须是2
                            if (invocation.getArguments().length != 2) {
                                throw new IllegalArgumentException("The redis set method arguments mismatch, must be two arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            //
                            byte[] key = String.valueOf(invocation.getArguments()[0]).getBytes();
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            // 对需要存入对值进行序列化
                            ObjectOutput value = getSerialization(url).serialize(url, output);
                            value.writeObject(invocation.getArguments()[1]);
                            // 存入值
                            resource.set(key, output.toByteArray());
                            // 设置该key过期时间，不能大于1000s
                            if (expiry > 1000) {
                                resource.expire(key, expiry / 1000);
                            }
                            return new RpcResult();
                        } else if (delete.equals(invocation.getMethodName())) {
                            // 如果是删除命令，则参数长度必须是1
                            if (invocation.getArguments().length != 1) {
                                throw new IllegalArgumentException("The redis delete method arguments mismatch, must only one arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            // 删除该值
                            resource.del(String.valueOf(invocation.getArguments()[0]).getBytes());
                            return new RpcResult();
                        } else {
                            // 否则抛出该操作不支持的异常
                            throw new UnsupportedOperationException("Unsupported method " + invocation.getMethodName() + " in redis service.");
                        }
                    } catch (Throwable t) {
                        RpcException re = new RpcException("Failed to invoke redis service method. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url + ", cause: " + t.getMessage(), t);
                        if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                            // 抛出超时异常
                            re.setCode(RpcException.TIMEOUT_EXCEPTION);
                        } else if (t instanceof JedisConnectionException || t instanceof IOException) {
                            // 抛出网络异常
                            re.setCode(RpcException.NETWORK_EXCEPTION);
                        } else if (t instanceof JedisDataException) {
                            // 抛出序列化异常
                            re.setCode(RpcException.SERIALIZATION_EXCEPTION);
                        }
                        throw re;
                    } finally {
                        if (resource != null) {
                            try {
                                jedisPool.returnResource(resource);
                            } catch (Throwable t) {
                                logger.warn("returnResource error: " + t.getMessage(), t);
                            }
                        }
                    }
                }

                @Override
                public void destroy() {
                    super.destroy();
                    try {
                        // 关闭连接池
                        jedisPool.destroy();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            };
        } catch (Throwable t) {
            throw new RpcException("Failed to refer redis service. interface: " + type.getName() + ", url: " + url + ", cause: " + t.getMessage(), t);
        }
    }

}
