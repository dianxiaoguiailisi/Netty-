/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 */
final class DefaultSelectStrategy implements SelectStrategy {
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    /**
     * 决定 EventLoop 在一次循环中，是调用 selectNow() 非阻塞地检查网络事件，
     * 还是返回 SelectStrategy.SELECT 常量，指示 EventLoop 去调用阻塞的 select() 方法。
     * @param selectSupplier 对 selector.selectNow() 方法的封装(立即返回当前就绪的网络事件数量，不会使线程阻塞)
     * @param hasTasks EventLoop 的任务队列（taskQueue 和 tailTasks）中是否有待执行的任务。
     * @return int
     * @throws Exception
     */
    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        //1.当 hasTasks == true 时（队列中有任务），则执行 selectSupplier.get()
        //2.当 hasTasks == false 时（队列中没有任务）,则返回常量 SelectStrategy.SELECT（通常值为 -1）。
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
    }
}
