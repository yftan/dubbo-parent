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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static com.alibaba.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.decodeInvocationArgument;

public class DecodeableRpcInvocation extends RpcInvocation implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcInvocation.class);

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
     * 请求
     */
    private Request request;

    /**
     * 是否解码
     */
    private volatile boolean hasDecoded;

    public DecodeableRpcInvocation(Channel channel, Request request, InputStream is, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(request, "request == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.request = request;
        this.inputStream = is;
        this.serializationType = id;
    }

    @Override
    public void decode() throws Exception {
        // 如果没有解码，则进行解码
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc invocation failed: " + e.getMessage(), e);
                }
                request.setBroken(true);
                request.setData(e);
            } finally {
                // 设置已经解码
                hasDecoded = true;
            }
        }
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        // 对数据进行反序列化
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);

        // dubbo版本
        String dubboVersion = in.readUTF();
        // 请求中放入dubbo版本
        request.setVersion(dubboVersion);
        // 附加值内加入dubbo版本，path以及版本号
        setAttachment(Constants.DUBBO_VERSION_KEY, dubboVersion);

        setAttachment(Constants.PATH_KEY, in.readUTF());
        setAttachment(Constants.VERSION_KEY, in.readUTF());

        // 设置方法名称
        setMethodName(in.readUTF());
        try {
            // 方法参数数组
            Object[] args;
            // 方法参数类型数组
            Class<?>[] pts;
            // 描述
            String desc = in.readUTF();
            // 如果为空，则方法参数数组和对方法参数类型数组都设置为空
            if (desc.length() == 0) {
                pts = DubboCodec.EMPTY_CLASS_ARRAY;
                args = DubboCodec.EMPTY_OBJECT_ARRAY;
            } else {
                // 分割成类，获得类数组
                pts = ReflectUtils.desc2classArray(desc);
                // 创建等长等数组
                args = new Object[pts.length];
                for (int i = 0; i < args.length; i++) {
                    try {
                        // 读取对象放入数组中
                        args[i] = in.readObject(pts[i]);
                    } catch (Exception e) {
                        if (log.isWarnEnabled()) {
                            log.warn("Decode argument failed: " + e.getMessage(), e);
                        }
                    }
                }
            }
            // 设置参数类型
            setParameterTypes(pts);

            Map<String, String> map = (Map<String, String>) in.readObject(Map.class);
            if (map != null && map.size() > 0) {
                // 获得所有附加值
                Map<String, String> attachment = getAttachments();
                if (attachment == null) {
                    attachment = new HashMap<String, String>();
                }
                // 把流中读到的配置放入附加值
                attachment.putAll(map);
                // 放回去
                setAttachments(attachment);
            }
            //decode argument ,may be callback
            for (int i = 0; i < args.length; i++) {
                // 如果是回调，则再一次解码
                args[i] = decodeInvocationArgument(channel, this, pts, i, args[i]);
            }

            setArguments(args);

        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
        return this;
    }

}
