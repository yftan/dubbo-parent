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
package com.alibaba.dubbo.registry.redis;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.rpc.RpcException;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RedisRegistry
 *
 */
public class RedisRegistry extends FailbackRegistry {

    // 日志记录
    private static final Logger logger = LoggerFactory.getLogger(RedisRegistry.class);

    // 默认的redis连接端口
    private static final int DEFAULT_REDIS_PORT = 6379;

    // 默认 Redis 根节点，涉及到的是dubbo的分组配置
    private final static String DEFAULT_ROOT = "dubbo";

    // 任务调度器
    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    //Redis Key 过期机制执行器
    private final ScheduledFuture<?> expireFuture;

    // Redis 根节点
    private final String root;

    // JedisPool集合，map 的key为 "ip:port"的形式
    private final Map<String, JedisPool> jedisPools = new ConcurrentHashMap<String, JedisPool>();

    // 通知器集合，key为 Root + Service的形式
    // 例如 /dubbo/com.alibaba.dubbo.demo.DemoService
    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<String, Notifier>();

    // 重连时间间隔，单位：ms
    private final int reconnectPeriod;

    // 过期周期，单位：ms
    private final int expirePeriod;

    // 是否通过监控中心，用于判断脏数据，脏数据由监控中心删除
    private volatile boolean admin = false;

    // 是否复制模式
    private boolean replicate;

    public RedisRegistry(URL url) {
        super(url);
        // 判断地址是否为空
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
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
        if (url.getParameter("max.wait", url.getParameter("timeout", 0)) > 0)
            //表示当引入一个jedis实例时，最大的等待时间，如果超过等待时间，则直接抛出JedisConnectionException；
            config.setMaxWaitMillis(url.getParameter("max.wait", url.getParameter("timeout", 0)));
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

        // 获取url中的集群配置
        String cluster = url.getParameter("cluster", "failover");
        if (!"failover".equals(cluster) && !"replicate".equals(cluster)) {
            throw new IllegalArgumentException("Unsupported redis cluster: " + cluster + ". The redis cluster only supported failover or replicate.");
        }
        // 设置是否为复制模式
        replicate = "replicate".equals(cluster);

        List<String> addresses = new ArrayList<String>();
        addresses.add(url.getAddress());
        // 备用地址
        String[] backups = url.getParameter(Constants.BACKUP_KEY, new String[0]);
        if (backups != null && backups.length > 0) {
            addresses.addAll(Arrays.asList(backups));
        }

        for (String address : addresses) {
            int i = address.indexOf(':');
            String host;
            int port;
            // 分割地址和端口号
            if (i > 0) {
                host = address.substring(0, i);
                port = Integer.parseInt(address.substring(i + 1));
            } else {
                // 没有端口的设置默认端口
                host = address;
                port = DEFAULT_REDIS_PORT;
            }
            // 创建连接池并加入集合
            this.jedisPools.put(address, new JedisPool(config, host, port,
                    url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT), StringUtils.isEmpty(url.getPassword()) ? null : url.getPassword(),
                    url.getParameter("db.index", 0)));
        }

        // 设置url携带的连接超时时间，如果没有配置，则设置默认为3s
        this.reconnectPeriod = url.getParameter(Constants.REGISTRY_RECONNECT_PERIOD_KEY, Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD);
        // 获取url中的分组配置，如果没有配置，则默认为dubbo
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(Constants.PATH_SEPARATOR)) {
            group = Constants.PATH_SEPARATOR + group;
        }
        if (!group.endsWith(Constants.PATH_SEPARATOR)) {
            group = group + Constants.PATH_SEPARATOR;
        }
        // 设置redis 的根节点
        this.root = group;

        // 获取过期周期配置，如果没有，则默认为60s
        this.expirePeriod = url.getParameter(Constants.SESSION_TIMEOUT_KEY, Constants.DEFAULT_SESSION_TIMEOUT);
        // 创建过期机制执行器
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    // 延长到期时间
                    deferExpired(); // Extend the expiration time
                } catch (Throwable t) { // Defensive fault tolerance
                    logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
                }
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    /**
     * 延长到期时间
     */
    private void deferExpired() {
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                // 获得连接池中的Jedis实例
                Jedis jedis = jedisPool.getResource();
                try {
                    // 遍历已经注册的服务url集合
                    for (URL url : new HashSet<URL>(getRegistered())) {
                        // 如果是非动态管理模式
                        if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                            // 获得分类路径
                            String key = toCategoryPath(url);
                            // 以hash 散列表的形式存储
                            if (jedis.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                                // 发布 Redis 注册事件
                                jedis.publish(key, Constants.REGISTER);
                            }
                        }
                    }
                    // 如果通过监控中心
                    if (admin) {
                        // 删除过时的脏数据
                        clean(jedis);
                    }
                    // 如果服务器端已同步数据，只需写入单台机器
                    if (!replicate) {
                        break;//  If the server side has synchronized data, just write a single machine
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
                logger.warn("Failed to write provider heartbeat to redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
    }

    /**
     * 监控中心负责删除过时的脏数据。
     * @param jedis
     */
    // The monitoring center is responsible for deleting outdated dirty data
    private void clean(Jedis jedis) {
        // 获得所有的服务
        Set<String> keys = jedis.keys(root + Constants.ANY_VALUE);
        if (keys != null && !keys.isEmpty()) {
            // 遍历所有的服务
            for (String key : keys) {
                // 返回hash表key对应的所有域和值
                // redis的key为服务名称和服务的类型。map中的key为URL地址，map中的value为过期时间，用于判断脏数据，脏数据由监控中心删除
                Map<String, String> values = jedis.hgetAll(key);
                if (values != null && values.size() > 0) {
                    boolean delete = false;
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        URL url = URL.valueOf(entry.getKey());
                        // 是否为动态节点
                        if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                            long expire = Long.parseLong(entry.getValue());
                            // 判断是否过期
                            if (expire < now) {
                                // 删除记录
                                jedis.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    // 取消注册
                    if (delete) {
                        jedis.publish(key, Constants.UNREGISTER);
                    }
                }
            }
        }
    }

    /**
     * 判断注册中心是否可用
     * @return
     */
    @Override
    public boolean isAvailable() {
        // 遍历连接池集合
        for (JedisPool jedisPool : jedisPools.values()) {
            try {
                // 从连接池中获得jedis实例
                Jedis jedis = jedisPool.getResource();
                try {
                    // 判断是否有redis服务器被连接着
                    // 只要有一台连接，则算注册中心可用
                    if (jedis.isConnected()) {
                        return true; // At least one single machine is available.
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            // 关闭过期执行器
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            // 关闭通知器
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                // 销毁连接池
                jedisPool.destroy();
            } catch (Throwable t) {
                logger.warn("Failed to destroy the redis registry client. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
        // 关闭任务调度器
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    @Override
    public void doRegister(URL url) {
        // 获得分类路径
        String key = toCategoryPath(url);
        // 获得URL字符串作为 Value
        String value = url.toFullString();
        // 计算过期时间
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        boolean success = false;
        RpcException exception = null;
        // 遍历连接池集合
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 写入 Redis Map 键
                    jedis.hset(key, value, expire);
                    // 发布 Redis 注册事件
                    // 这样订阅该 Key 的服务消费者和监控中心，就会实时从 Redis 读取该服务的最新数据。
                    jedis.publish(key, Constants.REGISTER);
                    success = true;
                    // 如果服务器端已同步数据，只需写入单台机器
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to register service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doUnregister(URL url) {
        // 获得分类路径
        String key = toCategoryPath(url);
        // 获得URL字符串作为 Value
        String value = url.toFullString();
        RpcException exception = null;
        boolean success = false;
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 删除redis中的记录
                    jedis.hdel(key, value);
                    // 发布redis取消注册事件
                    jedis.publish(key, Constants.UNREGISTER);
                    success = true;
                    // 如果服务器端已同步数据，只需写入单台机器
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to unregister service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        // 返回服务地址
        String service = toServicePath(url);
        // 获得通知器
        Notifier notifier = notifiers.get(service);
        // 如果没有该服务的通知器，则创建一个
        if (notifier == null) {
            Notifier newNotifier = new Notifier(service);
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            // 保证并发情况下，有且只有一个通知器启动
            if (notifier == newNotifier) {
                notifier.start();
            }
        }
        boolean success = false;
        RpcException exception = null;
        // 遍历连接池集合进行订阅，直到有一个订阅成功，仅仅向一个redis进行订阅
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 如果服务地址为*结尾，也就是处理所有的服务层发起的订阅
                    if (service.endsWith(Constants.ANY_VALUE)) {
                        admin = true;
                        // 获得分类层的集合 例如：/dubbo/com.alibaba.dubbo.demo.DemoService/providers
                        Set<String> keys = jedis.keys(service);
                        if (keys != null && !keys.isEmpty()) {
                            // 按照服务聚合url
                            Map<String, Set<String>> serviceKeys = new HashMap<String, Set<String>>();
                            for (String key : keys) {
                                // 获得服务路径，截掉多余部分
                                String serviceKey = toServicePath(key);
                                Set<String> sk = serviceKeys.get(serviceKey);
                                if (sk == null) {
                                    sk = new HashSet<String>();
                                    serviceKeys.put(serviceKey, sk);
                                }
                                sk.add(key);
                            }
                            // 按照每个服务层进行发起通知，因为服务地址为*结尾
                            for (Set<String> sk : serviceKeys.values()) {
                                doNotify(jedis, sk, url, Arrays.asList(listener));
                            }
                        }
                    } else {
                        // 处理指定的服务层发起的通知
                        doNotify(jedis, jedis.keys(service + Constants.PATH_SEPARATOR + Constants.ANY_VALUE), url, Arrays.asList(listener));
                    }
                    // 只在一个redis上进行订阅
                    success = true;
                    break; // Just read one server's data
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) { // Try the next server
                exception = new RpcException("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            // 虽然发生异常，但结果仍然成功
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    /**
     * 通知监听器，初始化的变化。（通知所有的监听器）
     * @param jedis
     * @param key
     */
    private void doNotify(Jedis jedis, String key) {
        // 遍历所有的通知器，调用重载方法今天通知
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<URL, Set<NotifyListener>>(getSubscribed()).entrySet()) {
            doNotify(jedis, Arrays.asList(key), entry.getKey(), new HashSet<NotifyListener>(entry.getValue()));
        }
    }

    /**
     * 通知监听器，初始化的变化。（通知指定的监听器）
     * @param jedis
     * @param keys
     * @param url
     * @param listeners
     */
    private void doNotify(Jedis jedis, Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<URL>();
        // 获得分类集合
        List<String> categories = Arrays.asList(url.getParameter(Constants.CATEGORY_KEY, new String[0]));
        // 通过url获得服务接口
        String consumerService = url.getServiceInterface();
        // 遍历分类路径，例如/dubbo/com.alibaba.dubbo.demo.DemoService/providers
        for (String key : keys) {
            // 判断服务是否匹配
            if (!Constants.ANY_VALUE.equals(consumerService)) {
                String prvoiderService = toServiceName(key);
                if (!prvoiderService.equals(consumerService)) {
                    continue;
                }
            }
            // 从分类路径上获得分类名
            String category = toCategoryName(key);
            // 判断订阅的分类是否包含该分类
            if (!categories.contains(Constants.ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            List<URL> urls = new ArrayList<URL>();
            // 返回所有的URL集合
            Map<String, String> values = jedis.hgetAll(key);
            if (values != null && values.size() > 0) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    // 判断是否为动态节点，因为动态节点不受过期限制。并且判断是否过期
                    if (!u.getParameter(Constants.DYNAMIC_KEY, true)
                            || Long.parseLong(entry.getValue()) >= now) {
                        // 判断url是否合法
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                        }
                    }
                }
            }
            // 若不存在匹配的url，则创建 `empty://` 的 URL返回，用于清空该服务的该分类。
            if (urls.isEmpty()) {
                urls.add(url.setProtocol(Constants.EMPTY_PROTOCOL)
                        .setAddress(Constants.ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(Constants.CATEGORY_KEY, category));
            }
            result.addAll(urls);
            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (result == null || result.isEmpty()) {
            return;
        }
        // 全部数据完成后，调用通知方法，来通知监听器
        for (NotifyListener listener : listeners) {
            notify(url, listener, result);
        }
    }

    /**
     * 从服务路径上获得服务名
     * @param categoryPath
     * @return
     */
    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }

    /**
     * 从分类路径上获得分类名
     * @param categoryPath
     * @return
     */
    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(Constants.PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    /**
     * 获得服务路径，主要截掉多余的部分
     * @param categoryPath
     * @return
     */
    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(Constants.PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(Constants.PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    /**
     * 返回服务地址
     * @param url
     * @return
     */
    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    /**
     * 获得分类路径
     * @param url
     * @return
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    private class NotifySub extends JedisPubSub {

        private final JedisPool jedisPool;

        public NotifySub(JedisPool jedisPool) {
            this.jedisPool = jedisPool;
        }

        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            // 如果是注册事件或者取消注册事件
            if (msg.equals(Constants.REGISTER)
                    || msg.equals(Constants.UNREGISTER)) {
                try {
                    Jedis jedis = jedisPool.getResource();
                    try {
                        // 通知监听器
                        doNotify(jedis, key);
                    } finally {
                        jedis.close();
                    }
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    private class Notifier extends Thread {

        // 服务名：Root + Service
        private final String service;
        // 需要忽略连接的次数
        private final AtomicInteger connectSkip = new AtomicInteger();
        // 已经忽略连接的次数
        private final AtomicInteger connectSkiped = new AtomicInteger();
        // 随机数
        private final Random random = new Random();
        // jedis实例
        private volatile Jedis jedis;
        // 是否是首次通知
        private volatile boolean first = true;
        // 是否运行中
        private volatile boolean running = true;
        // 连接次数随机数
        private volatile int connectRandom;

        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        /**
         * 重置忽略连接的信息
         */
        private void resetSkip() {
            connectSkip.set(0);
            connectSkiped.set(0);
            connectRandom = 0;
        }

        /**
         * 判断是否忽略
         * @return
         */
        private boolean isSkip() {
            // 获得忽略次数
            int skip = connectSkip.get(); // Growth of skipping times
            // 如果忽略次数超过10次，那么取随机数，加上一个10以内的随机数
            // 连接失败的次数越多，每一轮加大需要忽略的总次数，并且带有一定的随机性。
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = random.nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            // 自增忽略次数。若忽略次数不够，则继续忽略。
            if (connectSkiped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            // 增加需要忽略的次数
            connectSkip.incrementAndGet();
            // 重置已忽略次数和随机数
            connectSkiped.set(0);
            connectRandom = 0;
            return false;
        }

        @Override
        public void run() {
            // 当通知器正在运行中时
            while (running) {
                try {
                    // 如果不忽略连接
                    if (!isSkip()) {
                        try {
                            for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
                                JedisPool jedisPool = entry.getValue();
                                try {
                                    jedis = jedisPool.getResource();
                                    try {
                                        // 是否为监控中心
                                        if (service.endsWith(Constants.ANY_VALUE)) {
                                            // 如果不是第一次通知
                                            if (!first) {
                                                first = false;
                                                Set<String> keys = jedis.keys(service);
                                                if (keys != null && !keys.isEmpty()) {
                                                    for (String s : keys) {
                                                        // 通知
                                                        doNotify(jedis, s);
                                                    }
                                                }
                                                // 重置
                                                resetSkip();
                                            }
                                            // 批准订阅
                                            jedis.psubscribe(new NotifySub(jedisPool), service); // blocking
                                        } else {
                                            // 如果不是监控中心，并且不是第一次通知
                                            if (!first) {
                                                first = false;
                                                // 单独通知一个服务
                                                doNotify(jedis, service);
                                                // 重置
                                                resetSkip();
                                            }
                                            // 批准订阅
                                            jedis.psubscribe(new NotifySub(jedisPool), service + Constants.PATH_SEPARATOR + Constants.ANY_VALUE); // blocking
                                        }
                                        break;
                                    } finally {
                                        jedis.close();
                                    }
                                } catch (Throwable t) { // Retry another server
                                    logger.warn("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
                                    // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                    // 发生异常，说明 Redis 连接断开了，需要等待reconnectPeriod时间
                                    //通过这样的方式，避免执行，占用大量的 CPU 资源。
                                    sleep(reconnectPeriod);
                                }
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        public void shutdown() {
            try {
                // 更改状态
                running = false;
                // jedis断开连接
                jedis.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}
