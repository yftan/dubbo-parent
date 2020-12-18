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
package com.alibaba.dubbo.remoting.transport.grizzly;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractChannel;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.attributes.Attribute;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GrizzlyChannel
 *
 *
 */
final class GrizzlyChannel extends AbstractChannel {

    private static final Logger logger = LoggerFactory.getLogger(GrizzlyChannel.class);

    /**
     * 通道key
     */
    private static final String CHANNEL_KEY = GrizzlyChannel.class.getName() + ".CHANNEL";

    /**
     * 通道
     */
    private static final Attribute<GrizzlyChannel> ATTRIBUTE = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(CHANNEL_KEY);

    /**
     * Grizzly的连接实例
     */
    private final Connection<?> connection;

    /**
     * @param connection
     * @param url
     * @param handler
     */
    private GrizzlyChannel(Connection<?> connection, URL url, ChannelHandler handler) {
        super(url, handler);
        if (connection == null) {
            throw new IllegalArgumentException("grizzly connection == null");
        }
        // 设置连接实例
        this.connection = connection;
    }

    static GrizzlyChannel getOrAddChannel(Connection<?> connection, URL url, ChannelHandler handler) {
        if (connection == null) {
            return null;
        }
        // 获得通道实例
        GrizzlyChannel ret = ATTRIBUTE.get(connection);
        // 如果GrizzlyChannel为空，则新建
        if (ret == null) {
            ret = new GrizzlyChannel(connection, url, handler);
            // 如果通道是打开的
            if (connection.isOpen()) {
                ATTRIBUTE.set(connection, ret);
            }
        }
        return ret;
    }
    /**
     * 如果不连接，则移除通道
     * @param connection
     */
    static void removeChannelIfDisconnected(Connection<?> connection) {
        if (connection != null && !connection.isOpen()) {
            ATTRIBUTE.remove(connection);
        }
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) connection.getPeerAddress();
    }

    @Override
    public boolean isConnected() {
        return connection.isOpen();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) connection.getLocalAddress();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void send(Object message, boolean sent) throws RemotingException {
        super.send(message, sent);

        int timeout = 0;
        try {
            // 发送消息，获得GrizzlyFuture实例
            GrizzlyFuture future = connection.write(message);
            if (sent) {
                // 获得延迟多少时间获得响应
                timeout = getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
                // 获得请求的值
                future.get(timeout, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress()
                    + "in timeout(" + timeout + "ms) limit", e);
        } catch (Throwable e) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            // 移除通道当该通道不连接时
            removeChannelIfDisconnected(connection);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close grizzly channel " + connection);
            }
            connection.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasAttribute(String key) {
        return getAttribute(key) == null;
    }

    @Override
    public Object getAttribute(String key) {
        return Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(key).get(connection);
    }

    @Override
    public void setAttribute(String key, Object value) {
        Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(key).set(connection, value);
    }

    @Override
    public void removeAttribute(String key) {
        Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(key).remove(connection);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((connection == null) ? 0 : connection.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        GrizzlyChannel other = (GrizzlyChannel) obj;
        if (connection == null) {
            if (other.connection != null) return false;
        } else if (!connection.equals(other.connection)) return false;
        return true;
    }

    @Override
    public String toString() {
        return "GrizzlyChannel [connection=" + connection + "]";
    }

}
