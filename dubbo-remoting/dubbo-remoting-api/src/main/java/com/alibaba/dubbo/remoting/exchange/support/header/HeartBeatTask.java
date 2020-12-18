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

package com.alibaba.dubbo.remoting.exchange.support.header;

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Client;
import com.alibaba.dubbo.remoting.exchange.Request;

import java.util.Collection;

final class HeartBeatTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HeartBeatTask.class);

    /**
     * 通道管理
     */
    private ChannelProvider channelProvider;

    /**
     * 心跳间隔 单位：ms
     */
    private int heartbeat;

    /**
     * 心跳超时时间 单位：ms
     */
    private int heartbeatTimeout;

    HeartBeatTask(ChannelProvider provider, int heartbeat, int heartbeatTimeout) {
        this.channelProvider = provider;
        this.heartbeat = heartbeat;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    @Override
    public void run() {
        try {
            long now = System.currentTimeMillis();
            // 遍历所有通道
            for (Channel channel : channelProvider.getChannels()) {
                // 如果通道关闭了，则跳过
                if (channel.isClosed()) {
                    continue;
                }
                try {
                    // 最后一次接收到消息的时间戳
                    Long lastRead = (Long) channel.getAttribute(
                            HeaderExchangeHandler.KEY_READ_TIMESTAMP);
                    // 最后一次发送消息的时间戳
                    Long lastWrite = (Long) channel.getAttribute(
                            HeaderExchangeHandler.KEY_WRITE_TIMESTAMP);
                    // 如果最后一次接收或者发送消息到时间到现在的时间间隔超过了心跳间隔时间
                    if ((lastRead != null && now - lastRead > heartbeat)
                            || (lastWrite != null && now - lastWrite > heartbeat)) {
                        // 创建一个request
                        Request req = new Request();
                        // 设置版本号
                        req.setVersion(Version.getProtocolVersion());
                        // 设置需要得到响应
                        req.setTwoWay(true);
                        // 设置事件类型，为心跳事件
                        req.setEvent(Request.HEARTBEAT_EVENT);
                        // 发送心跳请求
                        channel.send(req);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Send heartbeat to remote channel " + channel.getRemoteAddress()
                                    + ", cause: The channel has no data-transmission exceeds a heartbeat period: " + heartbeat + "ms");
                        }
                    }
                    // 如果最后一次接收消息的时间到现在已经超过了超时时间
                    if (lastRead != null && now - lastRead > heartbeatTimeout) {
                        logger.warn("Close channel " + channel
                                + ", because heartbeat read idle time out: " + heartbeatTimeout + "ms");
                        // 如果该通道是客户端，也就是请求的服务器挂掉了，客户端尝试重连服务器
                        if (channel instanceof Client) {
                            try {
                                // 重新连接服务器
                                ((Client) channel).reconnect();
                            } catch (Exception e) {
                                //do nothing
                            }
                        } else {
                            // 如果不是客户端，也就是是服务端返回响应给客户端，但是客户端挂掉了，则服务端关闭客户端连接
                            channel.close();
                        }
                    }
                } catch (Throwable t) {
                    logger.warn("Exception when heartbeat to remote channel " + channel.getRemoteAddress(), t);
                }
            }
        } catch (Throwable t) {
            logger.warn("Unhandled exception when heartbeat, cause: " + t.getMessage(), t);
        }
    }

    interface ChannelProvider {
        // 获得所有的通道集合，需要心跳的通道数组
        Collection<Channel> getChannels();
    }

}

