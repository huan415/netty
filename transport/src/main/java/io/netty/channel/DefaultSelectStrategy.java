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
final class DefaultSelectStrategy implements SelectStrategy { //yangyc 默认选择策略实现类
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy(); //yangyc 单例

    private DefaultSelectStrategy() { }

    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception { //yangyc hasTasks==true:表示当前已经有任务,调用 IntSupplier#get() 方法，返回当前 Channel 新增的 IO 就绪事件的数量；
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT; //yangyc hasTasks==false: 阻塞 select Channel 感兴趣的就绪 IO 事件
    }
}
