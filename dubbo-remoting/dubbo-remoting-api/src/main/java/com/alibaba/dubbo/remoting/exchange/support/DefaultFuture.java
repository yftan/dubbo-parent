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
package com.alibaba.dubbo.remoting.exchange.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DefaultFuture.
 */
public class DefaultFuture implements ResponseFuture {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);

    /**
     * 通道集合
     */
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<Long, Channel>();

    /**
     * Future集合，key为请求编号
     */
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<Long, DefaultFuture>();

    static {
        // 开启一个后台扫描调用超时任务
        Thread th = new Thread(new RemotingInvocationTimeoutScan(), "DubboResponseTimeoutScanTimer");
        th.setDaemon(true);
        th.start();
    }

    // invoke id.
    /**
     * 请求编号
     */
    private final long id;
    /**
     * 通道
     */
    private final Channel channel;
    /**
     * 请求
     */
    private final Request request;
    /**
     * 超时
     */
    private final int timeout;
    /**
     * 锁
     */
    private final Lock lock = new ReentrantLock();
    /**
     * 完成情况，控制多线程的休眠与唤醒
     */
    private final Condition done = lock.newCondition();
    /**
     * 创建开始时间
     */
    private final long start = System.currentTimeMillis();
    /**
     * 发送请求时间
     */
    private volatile long sent;
    /**
     * 响应
     */
    private volatile Response response;
    /**
     * 回调
     */
    private volatile ResponseCallback callback;

    public DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        // 设置请求编号
        this.id = request.getId();
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // put into waiting map.，加入到等待集合中
        FUTURES.put(id, this);
        CHANNELS.put(id, channel);
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    /**
     * 检测是否有该通道
     * @param channel
     * @return
     */
    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    public static void sent(Channel channel, Request request) {
        // 获得请求对应到future
        DefaultFuture future = FUTURES.get(request.getId());
        // 如果不为空，则设定发送时间
        if (future != null) {
            future.doSent();
        }
    }

    /**
     * close a channel when a channel is inactive
     * directly return the unfinished requests.
     * 关闭不活跃的通道，并且返回请求未完成
     * @param channel channel to close
     */
    public static void closeChannel(Channel channel) {
        // 遍历通道集合
        for (long id : CHANNELS.keySet()) {
            if (channel.equals(CHANNELS.get(id))) {
                // 通过请求id获得future
                DefaultFuture future = getFuture(id);
                if (future != null && !future.isDone()) {
                    // 创建一个关闭通道的响应
                    Response disconnectResponse = new Response(future.getId());
                    disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
                    disconnectResponse.setErrorMessage("Channel " +
                            channel +
                            " is inactive. Directly return the unFinished request : " +
                            future.getRequest());
                    // 接收该关闭通道并且请求未完成的响应
                    DefaultFuture.received(channel, disconnectResponse);
                }
            }
        }
    }

    /**
     * 接收响应
     * @param channel
     * @param response
     */
    public static void received(Channel channel, Response response) {
        try {
            // future集合中移除该请求的future，（响应id和请求id一一对应的）
            DefaultFuture future = FUTURES.remove(response.getId());
            if (future != null) {
                // 接收响应结果
                future.doReceived(response);
            } else {
                logger.warn("The timeout response finally returned at "
                        + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                        + ", response " + response
                        + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
                        + " -> " + channel.getRemoteAddress()));
            }
        } finally {
            // 通道集合移除该请求对应的通道，代表着这一次请求结束
            CHANNELS.remove(response.getId());
        }
    }

    @Override
    public Object get() throws RemotingException {
        return get(timeout);
    }

    @Override
    public Object get(int timeout) throws RemotingException {
        // 超时时间默认为1s
        if (timeout <= 0) {
            timeout = Constants.DEFAULT_TIMEOUT;
        }
        // 如果请求没有完成，也就是还没有响应返回
        if (!isDone()) {
            long start = System.currentTimeMillis();
            // 获得锁
            lock.lock();
            try {
                // 轮询 等待请求是否完成
                while (!isDone()) {
                    // 线程阻塞等待
                    done.await(timeout, TimeUnit.MILLISECONDS);
                    // 如果请求完成或者超时，则结束
                    if (isDone() || System.currentTimeMillis() - start > timeout) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                // 释放锁
                lock.unlock();
            }
            // 如果没有收到响应，则抛出超时的异常
            if (!isDone()) {
                throw new TimeoutException(sent > 0, channel, getTimeoutMessage(false));
            }
        }
        // 返回响应
        return returnFromResponse();
    }

    /**
     * 取消请求
     */
    public void cancel() {
        // 创建一个取消请求的响应
        Response errorResult = new Response(id);
        errorResult.setErrorMessage("request future has been canceled.");
        response = errorResult;
        // 从集合中删除该请求
        FUTURES.remove(id);
        CHANNELS.remove(id);
    }

    @Override
    public boolean isDone() {
        return response != null;
    }

    @Override
    public void setCallback(ResponseCallback callback) {
        // 如果请求完成，则执行回调
        if (isDone()) {
            invokeCallback(callback);
        } else {
            boolean isdone = false;
            // 获得锁
            lock.lock();
            try {
                // 如果请求未完成，则设置回调
                if (!isDone()) {
                    this.callback = callback;
                } else {
                    isdone = true;
                }
            } finally {
                // 释放锁
                lock.unlock();
            }
            // 如果请求完成，则执行回调
            if (isdone) {
                invokeCallback(callback);
            }
        }
    }

    /**
     * 执行回调
     * @param c
     */
    private void invokeCallback(ResponseCallback c) {
        ResponseCallback callbackCopy = c;
        if (callbackCopy == null) {
            throw new NullPointerException("callback cannot be null.");
        }
        c = null;
        Response res = response;
        if (res == null) {
            throw new IllegalStateException("response cannot be null. url:" + channel.getUrl());
        }

        // 如果响应成功，返回码是20
        if (res.getStatus() == Response.OK) {
            try {
                // 使用响应结果执行 完成 后的逻辑
                callbackCopy.done(res.getResult());
            } catch (Exception e) {
                logger.error("callback invoke error .reasult:" + res.getResult() + ",url:" + channel.getUrl(), e);
            }
            //超时，回调处理成超时异常
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            try {
                TimeoutException te = new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
                // 回调处理异常
                callbackCopy.caught(te);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }
            // 其他情况处理成RemotingException异常
        } else {
            try {
                RuntimeException re = new RuntimeException(res.getErrorMessage());
                callbackCopy.caught(re);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }
        }
    }

    /**
     * 返回响应
     * @return
     * @throws RemotingException
     */
    private Object returnFromResponse() throws RemotingException {
        Response res = response;
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }
        // 如果正常返回，则返回响应结果
        if (res.getStatus() == Response.OK) {
            return res.getResult();
        }
        // 如果超时，则抛出超时异常
        if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            throw new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
        }
        // 其他 抛出RemotingException异常
        throw new RemotingException(channel, res.getErrorMessage());
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private long getStartTimestamp() {
        return start;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    /**
     * 接收结果
     * @param res
     */
    private void doReceived(Response res) {
        // 获得锁
        lock.lock();
        try {
            // 设置响应
            response = res;
            if (done != null) {
                // 唤醒等待
                done.signal();
            }
        } finally {
            // 释放锁
            lock.unlock();
        }
        if (callback != null) {
            // 执行回调
            invokeCallback(callback);
        }
    }

    /**
     * 生成并且返回超时异常信息
     * @param scan
     * @return
     */
    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())) + ","
                + (sent > 0 ? " client elapsed: " + (sent - start)
                + " ms, server elapsed: " + (nowTimestamp - sent)
                : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: "
                + timeout + " ms, request: " + request + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    /**
     * 扫描调用超时任务
     */
    private static class RemotingInvocationTimeoutScan implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    for (DefaultFuture future : FUTURES.values()) {
                        // 已经完成，跳过扫描
                        if (future == null || future.isDone()) {
                            continue;
                        }
                        // 超时
                        if (System.currentTimeMillis() - future.getStartTimestamp() > future.getTimeout()) {
                            // create exception response.，创建一个超时的响应
                            Response timeoutResponse = new Response(future.getId());
                            // set timeout status.，设置超时状态，是服务端侧超时还是客户端侧超时
                            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
                            // 设置错误信息
                            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
                            // handle response.，接收创建的超时响应
                            DefaultFuture.received(future.getChannel(), timeoutResponse);
                        }
                    }
                    // 睡眠
                    Thread.sleep(30);
                } catch (Throwable e) {
                    logger.error("Exception when scan the timeout invocation of remoting.", e);
                }
            }
        }
    }

}
