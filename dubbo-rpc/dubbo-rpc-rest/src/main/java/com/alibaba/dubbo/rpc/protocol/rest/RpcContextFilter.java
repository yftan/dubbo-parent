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
package com.alibaba.dubbo.rpc.protocol.rest;

import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.RpcContext;

import org.jboss.resteasy.spi.ResteasyProviderFactory;

import javax.annotation.Priority;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Priority(Integer.MIN_VALUE + 1)
public class RpcContextFilter implements ContainerRequestFilter, ClientRequestFilter {

    /**
     * 附加值key
     */
    private static final String DUBBO_ATTACHMENT_HEADER = "Dubbo-Attachments";

    // currently we use a single header to hold the attachments so that the total attachment size limit is about 8k
    /**
     * 目前我们使用单个标头来保存附件，以便总附件大小限制大约为8k
     */
    private static final int MAX_HEADER_SIZE = 8 * 1024;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // 获得request
        HttpServletRequest request = ResteasyProviderFactory.getContextData(HttpServletRequest.class);
        // 把它放到rpc上下文中
        RpcContext.getContext().setRequest(request);

        // this only works for servlet containers
        if (request != null && RpcContext.getContext().getRemoteAddress() == null) {
            // 设置远程地址
            RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
        }

        // 设置response
        RpcContext.getContext().setResponse(ResteasyProviderFactory.getContextData(HttpServletResponse.class));

        // 获得协议头信息
        String headers = requestContext.getHeaderString(DUBBO_ATTACHMENT_HEADER);
        // 分割协议头信息，把附加值分解开存入上下文中
        if (headers != null) {
            for (String header : headers.split(",")) {
                int index = header.indexOf("=");
                if (index > 0) {
                    String key = header.substring(0, index);
                    String value = header.substring(index + 1);
                    if (!StringUtils.isEmpty(key)) {
                        RpcContext.getContext().setAttachment(key.trim(), value.trim());
                    }
                }
            }
        }
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        int size = 0;
        // 遍历附加值
        for (Map.Entry<String, String> entry : RpcContext.getContext().getAttachments().entrySet()) {
            // 如果key或者value有出现=或者，则抛出异常
            if (entry.getValue().contains(",") || entry.getValue().contains("=")
                    || entry.getKey().contains(",") || entry.getKey().contains("=")) {
                throw new IllegalArgumentException("The attachments of " + RpcContext.class.getSimpleName() + " must not contain ',' or '=' when using rest protocol");
            }

            // TODO for now we don't consider the differences of encoding and server limit
            // 加入UTF-8配置，计算协议头大小
            size += entry.getValue().getBytes("UTF-8").length;
            // 如果大于限制，则抛出异常
            if (size > MAX_HEADER_SIZE) {
                throw new IllegalArgumentException("The attachments of " + RpcContext.class.getSimpleName() + " is too big");
            }

            // 拼接
            String attachments = entry.getKey() + "=" + entry.getValue();
            // 加入到请求头上
            requestContext.getHeaders().add(DUBBO_ATTACHMENT_HEADER, attachments);
        }
    }
}
