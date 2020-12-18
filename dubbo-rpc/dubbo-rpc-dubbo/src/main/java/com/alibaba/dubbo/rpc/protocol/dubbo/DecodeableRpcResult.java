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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Map;

public class DecodeableRpcResult extends RpcResult implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcResult.class);

    /**
     * 通道
     */
    private Channel channel;

    /**
     * 序列化类型
     */
    private byte serializationType;

    /**
     * 输入流
     */
    private InputStream inputStream;

    /**
     * 响应
     */
    private Response response;

    /**
     * 会话域
     */
    private Invocation invocation;

    /**
     * 是否解码
     */
    private volatile boolean hasDecoded;

    public DecodeableRpcResult(Channel channel, Response response, InputStream is, Invocation invocation, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(response, "response == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.response = response;
        this.inputStream = is;
        this.invocation = invocation;
        this.serializationType = id;
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        // 反序列化
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);
        
        byte flag = in.readByte();
        // 根据返回的不同结果来进行处理
        switch (flag) {
            case DubboCodec.RESPONSE_NULL_VALUE:
                // 返回结果为空
                break;
            case DubboCodec.RESPONSE_VALUE:
                //
                try {
                    // 获得返回类型数组
                    Type[] returnType = RpcUtils.getReturnTypes(invocation);
                    // 根据返回类型读取返回结果并且放入RpcResult
                    setValue(returnType == null || returnType.length == 0 ? in.readObject() :
                            (returnType.length == 1 ? in.readObject((Class<?>) returnType[0])
                                    : in.readObject((Class<?>) returnType[0], returnType[1])));
                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION:
                // 返回结果有异常
                try {
                    Object obj = in.readObject();
                    // 把异常放入RpcResult
                    if (obj instanceof Throwable == false)
                        throw new IOException("Response data error, expect Throwable, but get " + obj);
                    setException((Throwable) obj);
                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;
            case DubboCodec.RESPONSE_NULL_VALUE_WITH_ATTACHMENTS:
                // 返回值为空，但是有附加值
                try {
                    // 把附加值加入到RpcResult
                    setAttachments((Map<String, String>) in.readObject(Map.class));
                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;
            case DubboCodec.RESPONSE_VALUE_WITH_ATTACHMENTS:
                // 返回值
                try {
                    // 设置返回结果
                    Type[] returnType = RpcUtils.getReturnTypes(invocation);
                    setValue(returnType == null || returnType.length == 0 ? in.readObject() :
                            (returnType.length == 1 ? in.readObject((Class<?>) returnType[0])
                                    : in.readObject((Class<?>) returnType[0], returnType[1])));
                    // 设置附加值
                    setAttachments((Map<String, String>) in.readObject(Map.class));
                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS:
                // 返回结果有异常并且有附加值
                try {
                    // 设置异常
                    Object obj = in.readObject();
                    if (obj instanceof Throwable == false)
                        throw new IOException("Response data error, expect Throwable, but get " + obj);
                    setException((Throwable) obj);
                    // 设置附加值
                    setAttachments((Map<String, String>) in.readObject(Map.class));
                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;
            default:
                throw new IOException("Unknown result flag, expect '0' '1' '2', get " + flag);
        }
        if (in instanceof Cleanable) {
            ((Cleanable) in).cleanup();
        }
        return this;
    }

    @Override
    public void decode() throws Exception {
        // 如果没有解码
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                // 进行解码
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc result failed: " + e.getMessage(), e);
                }
                response.setStatus(Response.CLIENT_ERROR);
                response.setErrorMessage(StringUtils.toString(e));
            } finally {
                hasDecoded = true;
            }
        }
    }

}
