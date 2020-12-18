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
package com.alibaba.dubbo.remoting.transport.dispatcher.execution;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.ExecutionException;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelEventRunnable;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.ChannelState;
import com.alibaba.dubbo.remoting.transport.dispatcher.WrappedChannelHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Only request message will be dispatched to thread pool. Other messages like response, connect, disconnect,
 * heartbeat will be directly executed by I/O thread.
 */
public class ExecutionChannelHandler extends WrappedChannelHandler {

    public ExecutionChannelHandler(ChannelHandler handler, URL url) {
        super(handler, url);
    }

    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 获得线程池实例
        ExecutorService cexecutor = getExecutorService();
        // 如果消息是request类型，才会分发到线程池，其他消息，如响应，连接，断开连接，心跳将由I / O线程直接执行。
        if (message instanceof Request) {
            try {
                // 把请求消息分发到线程池
                cexecutor.execute(new ChannelEventRunnable(channel, handler, ChannelState.RECEIVED, message));
            } catch (Throwable t) {
                // FIXME: when the thread pool is full, SERVER_THREADPOOL_EXHAUSTED_ERROR cannot return properly,
                // therefore the consumer side has to wait until gets timeout. This is a temporary solution to prevent
                // this scenario from happening, but a better solution should be considered later.
                // 当线程池满了，SERVER_THREADPOOL_EXHAUSTED_ERROR错误无法正常返回
                // 因此消费者方必须等到超时。这是一种预防的临时解决方案，所以这里直接返回该错误
                if (t instanceof RejectedExecutionException) {
                    Request request = (Request) message;
                    if (request.isTwoWay()) {
                        String msg = "Server side(" + url.getIp() + "," + url.getPort()
                                + ") thread pool is exhausted, detail msg:" + t.getMessage();
                        Response response = new Response(request.getId(), request.getVersion());
                        response.setStatus(Response.SERVER_THREADPOOL_EXHAUSTED_ERROR);
                        response.setErrorMessage(msg);
                        channel.send(response);
                        return;
                    }
                }
                throw new ExecutionException(message, channel, getClass() + " error when process received event.", t);
            }
        } else {
            // 如果消息不是request类型，则直接处理
            handler.received(channel, message);
        }
    }
}
