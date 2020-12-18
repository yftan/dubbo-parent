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
package com.alibaba.dubbo.rpc.protocol.thrift.ext;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.rpc.protocol.thrift.ThriftCodec;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TIOStreamTransport;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MultiServiceProcessor implements TProcessor {

    private static final Logger logger = LoggerFactory.getLogger(MultiServiceProcessor.class);

    /**
     * 处理器集合
     */
    private ConcurrentMap<String, TProcessor> processorMap = new ConcurrentHashMap<String, TProcessor>();

    /**
     * 协议工厂
     */
    private TProtocolFactory protocolFactory = new TBinaryProtocol.Factory();

    public MultiServiceProcessor() {
    }

    @Override
    public boolean process(TProtocol in, TProtocol out) throws TException {

        // 获得十六进制的魔数
        short magic = in.readI16();

        // 如果不是规定的魔数，则打印错误日志，返回false
        if (magic != ThriftCodec.MAGIC) {
            logger.error("Unsupported magic " + magic);
            return false;
        }

        // 获得三十二进制魔数
        in.readI32();
        // 获得十六进制魔数
        in.readI16();
        // 获得版本
        byte version = in.readByte();
        // 获得服务名
        String serviceName = in.readString();
        // 获得id
        long id = in.readI64();

        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);

        // 创建基础运输TIOStreamTransport对象
        TIOStreamTransport transport = new TIOStreamTransport(bos);

        // 获得协议
        TProtocol protocol = protocolFactory.getProtocol(transport);

        // 从集合中取出处理器
        TProcessor processor = processorMap.get(serviceName);

        // 如果处理器为空，则打印错误，返回false
        if (processor == null) {
            logger.error("Could not find processor for service " + serviceName);
            return false;
        }

        // todo if exception
        // 获得结果
        boolean result = processor.process(in, protocol);

        ByteArrayOutputStream header = new ByteArrayOutputStream(512);

        // 协议头的传输器
        TIOStreamTransport headerTransport = new TIOStreamTransport(header);

        TProtocol headerProtocol = protocolFactory.getProtocol(headerTransport);

        // 写入16进制的魔数
        headerProtocol.writeI16(magic);
        // 写入32进制的Integer最大值
        headerProtocol.writeI32(Integer.MAX_VALUE);
        // 写入Short最大值的16进制
        headerProtocol.writeI16(Short.MAX_VALUE);
        // 写入版本号
        headerProtocol.writeByte(version);
        // 写入服务名
        headerProtocol.writeString(serviceName);
        // 写入id
        headerProtocol.writeI64(id);
        // 输出
        headerProtocol.getTransport().flush();

        out.writeI16(magic);
        out.writeI32(bos.size() + header.size());
        out.writeI16((short) (0xffff & header.size()));
        out.writeByte(version);
        out.writeString(serviceName);
        out.writeI64(id);

        out.getTransport().write(bos.toByteArray());
        out.getTransport().flush();

        return result;

    }

    public TProcessor addProcessor(Class service, TProcessor processor) {
        if (service != null && processor != null) {
            return processorMap.putIfAbsent(service.getName(), processor);
        }
        return processor;
    }

    public void setProtocolFactory(TProtocolFactory factory) {
        if (factory != null) {
            this.protocolFactory = factory;
        }
    }

}
