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
package com.alibaba.dubbo.remoting.zookeeper.zkclient;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.support.AbstractZookeeperClient;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import java.util.List;

public class ZkclientZookeeperClient extends AbstractZookeeperClient<IZkChildListener> {

    /**
     * zk客户端包装类
     */
    private final ZkClientWrapper client;

    /**
     * 连接状态
     */
    private volatile KeeperState state = KeeperState.SyncConnected;

    public ZkclientZookeeperClient(URL url) {
        super(url);
        // 新建一个zkclient包装类
        client = new ZkClientWrapper(url.getBackupAddress(), 30000);
        // 增加状态监听
        client.addListener(new IZkStateListener() {
            /**
             * 如果状态改变
             * @param state
             * @throws Exception
             */
            @Override
            public void handleStateChanged(KeeperState state) throws Exception {
                ZkclientZookeeperClient.this.state = state;
                // 如果状态变为了断开连接
                if (state == KeeperState.Disconnected) {
                    // 则修改状态
                    stateChanged(StateListener.DISCONNECTED);
                } else if (state == KeeperState.SyncConnected) {
                    stateChanged(StateListener.CONNECTED);
                }
            }

            @Override
            public void handleNewSession() throws Exception {
                // 状态变为重连
                stateChanged(StateListener.RECONNECTED);
            }
        });
        // 启动客户端
        client.start();
    }


    @Override
    public void createPersistent(String path) {
        try {
            // 递归创建节点
            client.createPersistent(path);
        } catch (ZkNodeExistsException e) {
        }
    }

    @Override
    public void createEphemeral(String path) {
        try {
            // 创建临时节点
            client.createEphemeral(path);
        } catch (ZkNodeExistsException e) {
        }
    }

    @Override
    public void delete(String path) {
        try {
            // 删除节点
            client.delete(path);
        } catch (ZkNoNodeException e) {
        }
    }

    @Override
    public List<String> getChildren(String path) {
        try {
            // 获得子节点
            return client.getChildren(path);
        } catch (ZkNoNodeException e) {
            return null;
        }
    }

    @Override
    public boolean checkExists(String path) {
        try {
            // 查看是否存在该节点
            return client.exists(path);
        } catch (Throwable t) {
        }
        return false;
    }

    @Override
    public boolean isConnected() {
        return state == KeeperState.SyncConnected;
    }

    @Override
    public void doClose() {
        client.close();
    }

    @Override
    public IZkChildListener createTargetChildListener(String path, final ChildListener listener) {
        return new IZkChildListener() {
            @Override
            public void handleChildChange(String parentPath, List<String> currentChilds)
                    throws Exception {
                listener.childChanged(parentPath, currentChilds);
            }
        };
    }

    /**
     * 增加子节点的监听器
     * @param path
     * @param listener
     * @return
     */
    @Override
    public List<String> addTargetChildListener(String path, final IZkChildListener listener) {
        return client.subscribeChildChanges(path, listener);
    }

    /**
     * 移除子节点的监听器
     * @param path
     * @param listener
     */
    @Override
    public void removeTargetChildListener(String path, IZkChildListener listener) {
        client.unsubscribeChildChanges(path, listener);
    }

}
