/*
 * Copyright 2013 The Netty Project
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

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    private static final long START_TIME = System.nanoTime(); //yangyc 定时任务时间起点，基于 START_TIME 做相对时间

    static long nanoTime() { //yangyc 当前时间,相对 START_TIME 来算的
        return System.nanoTime() - START_TIME;
    }

    static long deadlineNanos(long delay) { //yangyc 任务执行时间,相对 START_TIME 来算的，delay:延迟时间
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    static long initialNanoTime() {
        return START_TIME;
    }

    // set once when added to priority queue
    private long id; //yangyc 任务编号

    private long deadlineNanos; //yangyc 任务执行时间，即到了该时间，该任务就会被执行
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */ //yangyc =0:只执行一次; >0:按照计划执行时间计算; <0:按照实际执行时间计算
    private final long periodNanos; //yangyc 任务执行周期

    private int queueIndex = INDEX_NOT_IN_QUEUE;  //yangyc 队列编号

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime, long period) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    private static long validatePeriod(long period) {
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        return period;
    }

    ScheduledFutureTask<V> setId(long id) {
        if (this.id == 0L) {
            this.id = id;
        }
        return this;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    void setConsumed() {
        // Optimization to avoid checking system clock again
        // after deadline has passed and task has been dequeued
        if (periodNanos == 0) {
            assert nanoTime() >= deadlineNanos;
            deadlineNanos = 0L;
        }
    }

    public long delayNanos() { //yangyc 距离当前时间，还要多久可执行。若为负数，直接返回 0
        return deadlineToDelayNanos(deadlineNanos());
    }

    static long deadlineToDelayNanos(long deadlineNanos) {
        return deadlineNanos == 0L ? 0L : Math.max(0L, deadlineNanos - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) { //yangyc 距离指定时间，还要多久可执行。若为负数，直接返回 0
        return deadlineNanos == 0L ? 0L
                : Math.max(0L, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) { //yangyc 优先级队列排序 --- 按照 deadlineNanos、id 属性升序排序
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else {
            assert id != that.id;
            return 1;
        }
    }

    @Override
    public void run() {
        assert executor().inEventLoop();
        try {
            if (delayNanos() > 0L) {
                // Not yet expired, need to add or remove from queue
                if (isCancelled()) {
                    scheduledExecutor().scheduledTaskQueue().removeTyped(this);
                } else {
                    scheduledExecutor().scheduleFromEventLoop(this);
                }
                return;
            }
            if (periodNanos == 0) { //yangyc 执行周期为: 只执行一次
                if (setUncancellableInternal()) { //yangyc 设置任务不可取消
                    V result = runTask(); //yangyc 执行任务
                    setSuccessInternal(result); //yangyc 通知任务执行成功 --- 回调通知注册在定时任务上的监听器
                }
            } else { //yangyc 执行周期为: 固定周期
                // check if is done as it may was cancelled
                if (!isCancelled()) {  //yangyc 判断任务并未取消
                    runTask(); //yangyc 执行任务
                    if (!executor().isShutdown()) {
                        if (periodNanos > 0) {
                            deadlineNanos += periodNanos; //yangyc 计算下次执行时间
                        } else {
                            deadlineNanos = nanoTime() - periodNanos; //yangyc 计算下次执行时间
                        }
                        if (!isCancelled()) { //yangyc 判断任务并未取消
                            scheduledExecutor().scheduledTaskQueue().add(this); //yangyc 重新添加到任务队列，等待下次定时执行
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause); //yangyc 发生异常，通知任务执行失败
        }
    }

    private AbstractScheduledEventExecutor scheduledExecutor() {
        return (AbstractScheduledEventExecutor) executor();
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) { //yangyc 取消定时任务 --- 从定时任务队列移除自己
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
            scheduledExecutor().removeScheduled(this); //yangyc 取消成功，从定时任务队列移除自己
        }
        return canceled;
    }

    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) { //yangyc 取消定时任务
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex; //yangyc 获得 queueIndex 属性
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i; //yangyc 设置 queueIndex 属性
    }
}
