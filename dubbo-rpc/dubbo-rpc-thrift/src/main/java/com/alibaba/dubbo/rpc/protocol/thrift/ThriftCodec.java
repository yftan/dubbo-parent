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
package com.alibaba.dubbo.rpc.protocol.thrift;


import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.thrift.io.RandomAccessByteArrayOutputStream;

import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TIOStreamTransport;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thrift framed protocol codec.
 *
 * <pre>
 * |<-                                  message header                                  ->|<- message body ->|
 * +----------------+----------------------+------------------+---------------------------+------------------+
 * | magic (2 bytes)|message size (4 bytes)|head size(2 bytes)| version (1 byte) | header |   message body   |
 * +----------------+----------------------+------------------+---------------------------+------------------+
 * |<-                                               message size                                          ->|
 * </pre>
 *
 * <p>
 * <b>header fields in version 1</b>
 * <ol>
 *     <li>string - service name</li>
 *     <li>long   - dubbo request id</li>
 * </ol>
 * </p>
 */
public class ThriftCodec implements Codec2 {

    /**
     * 消息长度索引
     */
    public static final int MESSAGE_LENGTH_INDEX = 2;
    /**
     * 消息头长度索引
     */
    public static final int MESSAGE_HEADER_LENGTH_INDEX = 6;
    /**
     * 消息最短长度
     */
    public static final int MESSAGE_SHORTEST_LENGTH = 10;
    public static final String NAME = "thrift";
    /**
     * 类名生成参数
     */
    public static final String PARAMETER_CLASS_NAME_GENERATOR = "class.name.generator";
    /**
     * 版本
     */
    public static final byte VERSION = (byte) 1;
    /**
     * 魔数
     */
    public static final short MAGIC = (short) 0xdabc;
    /**
     * 请求参数集合
     */
    static final ConcurrentMap<Long, RequestData> cachedRequest =
            new ConcurrentHashMap<Long, RequestData>();
    /**
     * thrift序列号
     */
    private static final AtomicInteger THRIFT_SEQ_ID = new AtomicInteger(0);
    /**
     * 类缓存
     */
    private static final ConcurrentMap<String, Class<?>> cachedClass =
            new ConcurrentHashMap<String, Class<?>>();

    private static int nextSeqId() {
        return THRIFT_SEQ_ID.incrementAndGet();
    }

    // just for test
    static int getSeqId() {
        return THRIFT_SEQ_ID.get();
    }

    /**
     * 编码
     * @param channel 通道
     * @param buffer 数据
     * @param message 消息
     * @throws IOException
     */
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object message)
            throws IOException {

        // 如果消息是Request类型
        if (message instanceof Request) {
            // Request类型消息编码
            encodeRequest(channel, buffer, (Request) message);
        } else if (message instanceof Response) {
            // Response类型消息编码
            encodeResponse(channel, buffer, (Response) message);
        } else {
            throw new UnsupportedOperationException("Thrift codec only support encode " 
                    + Request.class.getName() + " and " + Response.class.getName());
        }

    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {

        int available = buffer.readableBytes();

        // 如果小于最小的长度，则还需要更多的输入
        if (available < MESSAGE_SHORTEST_LENGTH) {

            return DecodeResult.NEED_MORE_INPUT;

        } else {

            TIOStreamTransport transport = new TIOStreamTransport(new ChannelBufferInputStream(buffer));

            TBinaryProtocol protocol = new TBinaryProtocol(transport);

            short magic;
            int messageLength;

            // 对协议头中的魔数进行比对
            try {
//                protocol.readI32(); // skip the first message length
                byte[] bytes = new byte[4];
                transport.read(bytes, 0, 4);
                magic = protocol.readI16();
                messageLength = protocol.readI32();

            } catch (TException e) {
                throw new IOException(e.getMessage(), e);
            }

            if (MAGIC != magic) {
                throw new IOException("Unknown magic code " + magic);
            }

            if (available < messageLength) {
                return DecodeResult.NEED_MORE_INPUT;
            }

            return decode(protocol);

        }

    }

    /**
     * 解码
     * @param protocol
     * @return
     * @throws IOException
     */
    private Object decode(TProtocol protocol)
            throws IOException {

        // version
        String serviceName;
        long id;

        TMessage message;

        try {
            // 读取协议头中对内容
            protocol.readI16();
            protocol.readByte();
            serviceName = protocol.readString();
            id = protocol.readI64();
            message = protocol.readMessageBegin();
        } catch (TException e) {
            throw new IOException(e.getMessage(), e);
        }

        // 如果是回调
        if (message.type == TMessageType.CALL) {

            RpcInvocation result = new RpcInvocation();
            // 设置服务名和方法名
            result.setAttachment(Constants.INTERFACE_KEY, serviceName);
            result.setMethodName(message.name);

            String argsClassName = ExtensionLoader.getExtensionLoader(ClassNameGenerator.class)
                    .getExtension(ThriftClassNameGenerator.NAME).generateArgsClassName(serviceName, message.name);

            if (StringUtils.isEmpty(argsClassName)) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION,
                        "The specified interface name incorrect.");
            }

            // 从缓存中获得class类
            Class clazz = cachedClass.get(argsClassName);

            if (clazz == null) {
                try {

                    // 重新获得class类型
                    clazz = ClassHelper.forNameWithThreadContextClassLoader(argsClassName);

                    // 加入集合
                    cachedClass.putIfAbsent(argsClassName, clazz);

                } catch (ClassNotFoundException e) {
                    throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                }
            }

            TBase args;

            try {
                args = (TBase) clazz.newInstance();
            } catch (InstantiationException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

            try {
                args.read(protocol);
                protocol.readMessageEnd();
            } catch (TException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

            // 参数集合
            List<Object> parameters = new ArrayList<Object>();
            // 参数类型集合
            List<Class<?>> parameterTypes = new ArrayList<Class<?>>();
            int index = 1;

            while (true) {

                TFieldIdEnum fieldIdEnum = args.fieldForId(index++);

                if (fieldIdEnum == null) {
                    break;
                }

                String fieldName = fieldIdEnum.getFieldName();

                // 获得方法名
                String getMethodName = ThriftUtils.generateGetMethodName(fieldName);

                Method getMethod;

                try {
                    getMethod = clazz.getMethod(getMethodName);
                } catch (NoSuchMethodException e) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                }

                // 加入参数类型
                parameterTypes.add(getMethod.getReturnType());
                try {
                    parameters.add(getMethod.invoke(args));
                } catch (IllegalAccessException e) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                } catch (InvocationTargetException e) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                }

            }

            // 设置参数
            result.setArguments(parameters.toArray());
            // 设置参数类型
            result.setParameterTypes(parameterTypes.toArray(new Class[parameterTypes.size()]));

            // 创建一个新的请求
            Request request = new Request(id);
            // 把结果放入请求中
            request.setData(result);

            // 放入集合中
            cachedRequest.putIfAbsent(id,
                    RequestData.create(message.seqid, serviceName, message.name));

            return request;

            // 如果是抛出异常
        } else if (message.type == TMessageType.EXCEPTION) {

            TApplicationException exception;

            try {
                // 读取异常
                exception = TApplicationException.read(protocol);
                protocol.readMessageEnd();
            } catch (TException e) {
                throw new IOException(e.getMessage(), e);
            }

            // 创建结果
            RpcResult result = new RpcResult();

            // 设置异常
            result.setException(new RpcException(exception.getMessage()));

            // 创建Response响应
            Response response = new Response();

            // 把结果放入
            response.setResult(result);

            // 加入唯一id
            response.setId(id);

            return response;

            // 如果类型是回应
        } else if (message.type == TMessageType.REPLY) {

            // 获得结果的类名
            String resultClassName = ExtensionLoader.getExtensionLoader(ClassNameGenerator.class)
                    .getExtension(ThriftClassNameGenerator.NAME).generateResultClassName(serviceName, message.name);

            if (StringUtils.isEmpty(resultClassName)) {
                throw new IllegalArgumentException("Could not infer service result class name from service name " 
                        + serviceName + ", the service name you specified may not generated by thrift idl compiler");
            }

            // 获得class类型
            Class<?> clazz = cachedClass.get(resultClassName);

            // 如果为空，则重新获取
            if (clazz == null) {

                try {

                    clazz = ClassHelper.forNameWithThreadContextClassLoader(resultClassName);

                    cachedClass.putIfAbsent(resultClassName, clazz);

                } catch (ClassNotFoundException e) {
                    throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                }

            }

            TBase<?, ? extends TFieldIdEnum> result;
            try {
                result = (TBase<?, ?>) clazz.newInstance();
            } catch (InstantiationException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

            try {
                result.read(protocol);
                protocol.readMessageEnd();
            } catch (TException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

            Object realResult = null;

            int index = 0;

            while (true) {

                TFieldIdEnum fieldIdEnum = result.fieldForId(index++);

                if (fieldIdEnum == null) {
                    break;
                }

                Field field;

                try {
                    field = clazz.getDeclaredField(fieldIdEnum.getFieldName());
                    field.setAccessible(true);
                } catch (NoSuchFieldException e) {
                    throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                }

                try {
                    // 获得真实的结果
                    realResult = field.get(result);
                } catch (IllegalAccessException e) {
                    throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                }

                if (realResult != null) {
                    break;
                }

            }

            // 创建响应
            Response response = new Response();

            // 设置唯一id
            response.setId(id);

            // 创建结果
            RpcResult rpcResult = new RpcResult();

            // 用RpcResult包裹结果
            if (realResult instanceof Throwable) {
                rpcResult.setException((Throwable) realResult);
            } else {
                rpcResult.setValue(realResult);
            }

            // 设置结果
            response.setResult(rpcResult);

            return response;

        } else {
            // Impossible
            throw new IOException();
        }

    }

    /**
     * Request类型消息编码
     * @param channel
     * @param buffer
     * @param request
     * @throws IOException
     */
    private void encodeRequest(Channel channel, ChannelBuffer buffer, Request request)
            throws IOException {

        // 获得会话域
        RpcInvocation inv = (RpcInvocation) request.getData();

        // 获得下一个id
        int seqId = nextSeqId();

        // 获得服务名
        String serviceName = inv.getAttachment(Constants.INTERFACE_KEY);

        // 如果是空的 则抛出异常
        if (StringUtils.isEmpty(serviceName)) {
            throw new IllegalArgumentException("Could not find service name in attachment with key " 
                    + Constants.INTERFACE_KEY);
        }

        // 创建TMessage对象
        TMessage message = new TMessage(
                inv.getMethodName(),
                TMessageType.CALL,
                seqId);

        // 获得方法参数
        String methodArgs = ExtensionLoader.getExtensionLoader(ClassNameGenerator.class)
                .getExtension(channel.getUrl().getParameter(ThriftConstants.CLASS_NAME_GENERATOR_KEY, ThriftClassNameGenerator.NAME))
                .generateArgsClassName(serviceName, inv.getMethodName());

        // 如果是空，则抛出异常
        if (StringUtils.isEmpty(methodArgs)) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION,
                    "Could not encode request, the specified interface may be incorrect.");
        }

        // 从缓存中取出类型
        Class<?> clazz = cachedClass.get(methodArgs);

        if (clazz == null) {

            try {

                // 重新获得类型
                clazz = ClassHelper.forNameWithThreadContextClassLoader(methodArgs);

                // 加入缓存
                cachedClass.putIfAbsent(methodArgs, clazz);

            } catch (ClassNotFoundException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

        }

        // 生成的Thrift对象的通用基接口
        TBase args;

        try {
            args = (TBase) clazz.newInstance();
        } catch (InstantiationException e) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
        } catch (IllegalAccessException e) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
        }

        // 遍历参数
        for (int i = 0; i < inv.getArguments().length; i++) {

            Object obj = inv.getArguments()[i];

            if (obj == null) {
                continue;
            }

            TFieldIdEnum field = args.fieldForId(i + 1);

            // 生成set方法名
            String setMethodName = ThriftUtils.generateSetMethodName(field.getFieldName());

            Method method;

            try {
                // 获得方法
                method = clazz.getMethod(setMethodName, inv.getParameterTypes()[i]);
            } catch (NoSuchMethodException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

            try {
                // 调用下一个调用链
                method.invoke(args, obj);
            } catch (IllegalAccessException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            } catch (InvocationTargetException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

        }

        // 创建一个随机访问数组输出流
        RandomAccessByteArrayOutputStream bos = new RandomAccessByteArrayOutputStream(1024);

        // 创建传输器
        TIOStreamTransport transport = new TIOStreamTransport(bos);

        // 创建协议
        TBinaryProtocol protocol = new TBinaryProtocol(transport);

        int headerLength, messageLength;

        byte[] bytes = new byte[4];
        try {
            // 开始编码
            // magic
            protocol.writeI16(MAGIC);
            // message length placeholder
            protocol.writeI32(Integer.MAX_VALUE);
            // message header length placeholder
            protocol.writeI16(Short.MAX_VALUE);
            // version
            protocol.writeByte(VERSION);
            // service name
            protocol.writeString(serviceName);
            // dubbo request id
            protocol.writeI64(request.getId());
            protocol.getTransport().flush();
            // header size
            headerLength = bos.size();

            // 对body内容进行编码
            // message body
            protocol.writeMessageBegin(message);
            args.write(protocol);
            protocol.writeMessageEnd();
            protocol.getTransport().flush();
            int oldIndex = messageLength = bos.size();

            // fill in message length and header length
            try {
                TFramedTransport.encodeFrameSize(messageLength, bytes);
                bos.setWriteIndex(MESSAGE_LENGTH_INDEX);
                protocol.writeI32(messageLength);
                bos.setWriteIndex(MESSAGE_HEADER_LENGTH_INDEX);
                protocol.writeI16((short) (0xffff & headerLength));
            } finally {
                bos.setWriteIndex(oldIndex);
            }

        } catch (TException e) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
        }

        buffer.writeBytes(bytes);
        buffer.writeBytes(bos.toByteArray());

    }

    private void encodeResponse(Channel channel, ChannelBuffer buffer, Response response)
            throws IOException {

        // 获得结果
        RpcResult result = (RpcResult) response.getResult();

        // 获得请求
        RequestData rd = cachedRequest.get(response.getId());

        // 获得结果的类名
        String resultClassName = ExtensionLoader.getExtensionLoader(ClassNameGenerator.class).getExtension(
                channel.getUrl().getParameter(ThriftConstants.CLASS_NAME_GENERATOR_KEY, ThriftClassNameGenerator.NAME))
                .generateResultClassName(rd.serviceName, rd.methodName);

        // 如果为空，则序列化失败
        if (StringUtils.isEmpty(resultClassName)) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION,
                    "Could not encode response, the specified interface may be incorrect.");
        }

        // 获得类型
        Class clazz = cachedClass.get(resultClassName);

        // 如果为空，则重新获取
        if (clazz == null) {

            try {
                clazz = ClassHelper.forNameWithThreadContextClassLoader(resultClassName);
                cachedClass.putIfAbsent(resultClassName, clazz);
            } catch (ClassNotFoundException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

        }

        TBase resultObj;

        try {
            // 加载该类
            resultObj = (TBase) clazz.newInstance();
        } catch (InstantiationException e) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
        } catch (IllegalAccessException e) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
        }

        TApplicationException applicationException = null;
        TMessage message;

        // 如果结果有异常抛出
        if (result.hasException()) {
            Throwable throwable = result.getException();
            int index = 1;
            boolean found = false;
            while (true) {
                TFieldIdEnum fieldIdEnum = resultObj.fieldForId(index++);
                if (fieldIdEnum == null) {
                    break;
                }
                String fieldName = fieldIdEnum.getFieldName();
                String getMethodName = ThriftUtils.generateGetMethodName(fieldName);
                String setMethodName = ThriftUtils.generateSetMethodName(fieldName);
                Method getMethod;
                Method setMethod;
                try {
                    // 获得get方法
                    getMethod = clazz.getMethod(getMethodName);
                    // 如果返回类型和异常类型一样，则创建set方法，并且调用下一个调用链
                    if (getMethod.getReturnType().equals(throwable.getClass())) {
                        found = true;
                        setMethod = clazz.getMethod(setMethodName, throwable.getClass());
                        setMethod.invoke(resultObj, throwable);
                    }
                } catch (NoSuchMethodException e) {
                    throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                } catch (InvocationTargetException e) {
                    throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                } catch (IllegalAccessException e) {
                    throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
                }
            }

            if (!found) {
                // 创建TApplicationException异常
                applicationException = new TApplicationException(throwable.getMessage());
            }

        } else {
            // 获得真实的结果
            Object realResult = result.getResult();
            // result field id is 0
            String fieldName = resultObj.fieldForId(0).getFieldName();
            String setMethodName = ThriftUtils.generateSetMethodName(fieldName);
            String getMethodName = ThriftUtils.generateGetMethodName(fieldName);
            Method getMethod;
            Method setMethod;
            try {
                // 创建get和set方法
                getMethod = clazz.getMethod(getMethodName);
                setMethod = clazz.getMethod(setMethodName, getMethod.getReturnType());
                setMethod.invoke(resultObj, realResult);
            } catch (NoSuchMethodException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            } catch (InvocationTargetException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
            }

        }

        if (applicationException != null) {
            message = new TMessage(rd.methodName, TMessageType.EXCEPTION, rd.id);
        } else {
            message = new TMessage(rd.methodName, TMessageType.REPLY, rd.id);
        }

        RandomAccessByteArrayOutputStream bos = new RandomAccessByteArrayOutputStream(1024);

        TIOStreamTransport transport = new TIOStreamTransport(bos);

        TBinaryProtocol protocol = new TBinaryProtocol(transport);

        int messageLength;
        int headerLength;

        //编码
        byte[] bytes = new byte[4];
        try {
            // magic
            protocol.writeI16(MAGIC);
            // message length
            protocol.writeI32(Integer.MAX_VALUE);
            // message header length
            protocol.writeI16(Short.MAX_VALUE);
            // version
            protocol.writeByte(VERSION);
            // service name
            protocol.writeString(rd.serviceName);
            // id
            protocol.writeI64(response.getId());
            protocol.getTransport().flush();
            headerLength = bos.size();

            // message
            protocol.writeMessageBegin(message);
            switch (message.type) {
                case TMessageType.EXCEPTION:
                    applicationException.write(protocol);
                    break;
                case TMessageType.REPLY:
                    resultObj.write(protocol);
                    break;
            }
            protocol.writeMessageEnd();
            protocol.getTransport().flush();
            int oldIndex = messageLength = bos.size();

            try {
                TFramedTransport.encodeFrameSize(messageLength, bytes);
                bos.setWriteIndex(MESSAGE_LENGTH_INDEX);
                protocol.writeI32(messageLength);
                bos.setWriteIndex(MESSAGE_HEADER_LENGTH_INDEX);
                protocol.writeI16((short) (0xffff & headerLength));
            } finally {
                bos.setWriteIndex(oldIndex);
            }

        } catch (TException e) {
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e);
        }

        buffer.writeBytes(bytes);
        buffer.writeBytes(bos.toByteArray());

    }

    /**
     * 请求参数实体
     */
    static class RequestData {
        /**
         * 请求id
         */
        int id;
        /**
         * 服务名
         */
        String serviceName;
        /**
         * 方法名
         */
        String methodName;

        static RequestData create(int id, String sn, String mn) {
            RequestData result = new RequestData();
            result.id = id;
            result.serviceName = sn;
            result.methodName = mn;
            return result;
        }

    }

}
