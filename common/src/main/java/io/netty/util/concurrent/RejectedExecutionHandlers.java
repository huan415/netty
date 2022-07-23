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

import io.netty.util.internal.ObjectUtil;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Expose helper methods which create different {@link RejectedExecutionHandler}s.
 */
public final class RejectedExecutionHandlers { //yangyc RejectedExecutionHandler 实现类枚举, 目前有 2 种实现类; 1:REJECT;2:backoff
    private static final RejectedExecutionHandler REJECT = new RejectedExecutionHandler() { //yangyc 默认的拒绝策略，直接抛异常
        @Override
        public void rejected(Runnable task, SingleThreadEventExecutor executor) {
            throw new RejectedExecutionException();
        }
    };

    private RejectedExecutionHandlers() { }

    /**
     * Returns a {@link RejectedExecutionHandler} that will always just throw a {@link RejectedExecutionException}.
     */
    public static RejectedExecutionHandler reject() {
        return REJECT;
    }

    /**
     * Tries to backoff when the task can not be added due restrictions for an configured amount of time. This
     * is only done if the task was added from outside of the event loop which means
     * {@link EventExecutor#inEventLoop()} returns {@code false}.
     */
    public static RejectedExecutionHandler backoff(final int retries, long backoffAmount, TimeUnit unit) { //yangyc 拒绝策略，多次尝试添加到任务队列
        ObjectUtil.checkPositive(retries, "retries");
        final long backOffNanos = unit.toNanos(backoffAmount);
        return new RejectedExecutionHandler() {
            @Override
            public void rejected(Runnable task, SingleThreadEventExecutor executor) {
                if (!executor.inEventLoop()) { //yangyc 非 EventLoop 线程中。如果在 EventLoop 线程中，就无法执行任务，这就导致完全无法重试了。
                    for (int i = 0; i < retries; i++) { //yangyc 循环多次尝试添加到队列中
                        // Try to wake up the executor so it will empty its task queue.
                        executor.wakeup(false);  //yangyc 唤醒执行器，进行任务执行

                        LockSupport.parkNanos(backOffNanos); //yangyc 阻塞等待
                        if (executor.offerTask(task)) { //yangyc 添加任务
                            return;
                        }
                    }
                }
                // Either we tried to add the task from within the EventLoop or we was not able to add it even with
                // backoff.
                throw new RejectedExecutionException(); //yangyc 多次尝试添加失败，抛出 RejectedExecutionException 异常
            }
        };
    }
}
