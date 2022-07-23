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
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory(); //yangyc 单例

    private DefaultEventExecutorChooserFactory() { }

    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) { //yangyc  chooser工厂类创建 EventExecutor 选择器，有两种：PowerOfTwoEventExecutorChooser和GenericEventExecutorChooser
        if (isPowerOfTwo(executors.length)) {
            return new PowerOfTwoEventExecutorChooser(executors); //yangyc 内部类
        } else {
            return new GenericEventExecutorChooser(executors); //yangyc 内部类
        }
    }

    private static boolean isPowerOfTwo(int val) { //yangyc 判断 EventExecutor 数组的大小是否为 2 的幂次方
        return (val & -val) == val;
    }

    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger(); //yangyc 自增序列
        private final EventExecutor[] executors; //yangyc EventExecutor 数组

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        // Use a 'long' counter to avoid non-round-robin behaviour at the 32-bit overflow boundary.
        // The 64-bit long solves this by placing the overflow so far into the future, that no system
        // will encounter this in practice.
        private final AtomicLong idx = new AtomicLong();  //yangyc 自增序列
        private final EventExecutor[] executors; //yangyc EventExecutor 数组

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            return executors[(int) Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}
