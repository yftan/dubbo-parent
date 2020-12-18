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
package com.alibaba.dubbo.remoting.transport.netty;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;

import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NettyHandler
 */
@Sharable
public class NettyHandler extends SimpleChannelHandler {

    /**
     * 通道集合，key是主机地址 ip:port
     */
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>(); // <ip:port, channel>

    /**
     * url对象
     */
    private final URL url;

    /**
     * 通道
     */
    private final ChannelHandler handler;

    public NettyHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // 获得通道实例
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.getChannel(), url, handler);
        try {
            if (channel != null) {
                // 保存该通道，加入到集合中
                channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.getChannel().getRemoteAddress()), channel);
            }
            // 连接
            handler.connected(channel);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.getChannel());
        }
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // 获得通道实例
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.getChannel(), url, handler);
        try {
            //从集合中移除
            channels.remove(NetUtils.toAddressString((InetSocketAddress) ctx.getChannel().getRemoteAddress()));
            // 断开连接
            handler.disconnected(channel);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.getChannel());
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        // 获得通道实例
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.getChannel(), url, handler);
        try {
            // 接收消息
            handler.received(channel, e.getMessage());
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.getChannel());
        }
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        super.writeRequested(ctx, e);
        // 获得通道实例
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.getChannel(), url, handler);
        try {
            // 发送消息
            handler.sent(channel, e.getMessage());
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.getChannel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        // 获得通道实例
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.getChannel(), url, handler);
        try {
            // 捕获异常
            handler.caught(channel, e.getCause());
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.getChannel());
        }
    }

}