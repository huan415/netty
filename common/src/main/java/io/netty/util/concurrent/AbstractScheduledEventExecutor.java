/*
 * Copyright 2015 The Netty Project
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
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PriorityQueue;

import static io.netty.util.concurrent.ScheduledFutureTask.deadlineNanos;

import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link EventExecutor}s that want to support scheduling.
 */
public abstract class AbstractScheduledEventExecutor extends AbstractEventExecutor { //yangyc 支持定时任务的 EventExecutor 的抽象类, 继承抽象的事件执行器
    private static final Comparator<ScheduledFutureTask<?>> SCHEDULED_FUTURE_TASK_COMPARATOR =
            new Comparator<ScheduledFutureTask<?>>() {
                @Override
                public int compare(ScheduledFutureTask<?> o1, ScheduledFutureTask<?> o2) {
                    return o1.compareTo(o2);
                }
            }; //yangyc 定时任务排序器 --- 队列首个任务，就是第一个需要执行的定时任务

   static final Runnable WAKEUP_TASK = new Runnable() {
       @Override
       public void run() { } // Do nothing yangyc 这是一个空的 Runnable 实现类。仅仅用于唤醒基于 taskQueue 阻塞拉取的 EventLoop 实现类
    };

    PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue; //yangyc 定时任务队列 --- 队列首个任务，就是第一个需要执行的定时任务

    long nextTaskId; //yangyc 任务序号生, 递增

    protected AbstractScheduledEventExecutor() {
    }

    protected AbstractScheduledEventExecutor(EventExecutorGroup parent) {
        super(parent);
    }

    protected static long nanoTime() {
        return ScheduledFutureTask.nanoTime(); //yangyc 当前时间
    }

    /**
     * Given an arbitrary deadline {@code deadlineNanos}, calculate the number of nano seconds from now
     * {@code deadlineNanos} would expire.
     * @param deadlineNanos An arbitrary deadline in nano seconds.
     * @return the number of nano seconds from now {@code deadlineNanos} would expire.
     */
    protected static long deadlineToDelayNanos(long deadlineNanos) {
        return ScheduledFutureTask.deadlineToDelayNanos(deadlineNanos);
    }

    /**
     * The initial value used for delay and computations based upon a monatomic time source.
     * @return initial value used for delay and computations based upon a monatomic time source.
     */
    protected static long initialNanoTime() {
        return ScheduledFutureTask.initialNanoTime();
    }

    PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue() { //yangyc 获得定时任务队列
        if (scheduledTaskQueue == null) { //yangyc 若未初始化，则进行创建
            scheduledTaskQueue = new DefaultPriorityQueue<ScheduledFutureTask<?>>(
                    SCHEDULED_FUTURE_TASK_COMPARATOR,
                    // Use same initial capacity as java.util.PriorityQueue
                    11);
        }
        return scheduledTaskQueue;
    }

    private static boolean isNullOrEmpty(Queue<ScheduledFutureTask<?>> queue) {
        return queue == null || queue.isEmpty();
    }

    /**
     * Cancel all scheduled tasks.
     *
     * This method MUST be called only when {@link #inEventLoop()} is {@code true}.
     */
    protected void cancelScheduledTasks() { //yangyc 取消定时任务队列的所有任务
        assert inEventLoop();
        PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        if (isNullOrEmpty(scheduledTaskQueue)) {
            return; //yangyc 若队列为空，直接返回
        }

        final ScheduledFutureTask<?>[] scheduledTasks =
                scheduledTaskQueue.toArray(new ScheduledFutureTask<?>[0]);

        for (ScheduledFutureTask<?> task: scheduledTasks) {
            task.cancelWithoutRemove(false); //yangyc 循环，取消所有任务
        }

        scheduledTaskQueue.clearIgnoringIndexes();
    }

    /**
     * @see #pollScheduledTask(long)
     */
    protected final Runnable pollScheduledTask() { //yangyc 获得指定时间内，定时任务队列首个可执行的任务，并且从队列中移除
        return pollScheduledTask(nanoTime()); //yangyc nanoTime()当前时间
    }

    /**
     * Return the {@link Runnable} which is ready to be executed with the given {@code nanoTime}.
     * You should use {@link #nanoTime()} to retrieve the correct {@code nanoTime}.
     */
    protected final Runnable pollScheduledTask(long nanoTime) { //yangyc 获得指定时间内，定时任务队列首个可执行的任务，并且从队列中移除
        assert inEventLoop();

        ScheduledFutureTask<?> scheduledTask = peekScheduledTask(); //yangyc 获得队列首个定时任务, 因为队列首个任务，就是第一个需要执行的定时任务
        if (scheduledTask == null || scheduledTask.deadlineNanos() - nanoTime > 0) { //yangyc 若获取不到或者不在指定时间内，直接返回
            return null;
        }
        scheduledTaskQueue.remove(); //yangyc 移除任务
        scheduledTask.setConsumed();
        return scheduledTask;
    }

    /**
     * Return the nanoseconds until the next scheduled task is ready to be run or {@code -1} if no task is scheduled.
     */
    protected final long nextScheduledTaskNano() { //yangyc 定时任务队列，距离当前时间，还要多久可执行
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask(); //yangyc 获得队列首个定时任务, 因为队列首个任务，就是第一个需要执行的定时任务
        return scheduledTask != null ? scheduledTask.delayNanos() : -1; //yangyc 距离当前时间，还要多久可执行。若为负数，直接返回 0
    }

    /**
     * Return the deadline (in nanoseconds) when the next scheduled task is ready to be run or {@code -1}
     * if no task is scheduled.
     */
    protected final long nextScheduledTaskDeadlineNanos() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        return scheduledTask != null ? scheduledTask.deadlineNanos() : -1; //yangyc 周期任务队列内有任务，返回对头任务的截止时间，否则返回-1
    }

    final ScheduledFutureTask<?> peekScheduledTask() { //yangyc 获得队列首个定时任务, 因为队列首个任务，就是第一个需要执行的定时任务
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        return scheduledTaskQueue != null ? scheduledTaskQueue.peek() : null;
    }

    /**
     * Returns {@code true} if a scheduled task is ready for processing.
     */
    protected final boolean hasScheduledTasks() { //yangyc 判断是否有可执行的定时任务
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask(); //yangyc 获得队列首个定时任务, 因为队列首个任务，就是第一个需要执行的定时任务
        return scheduledTask != null && scheduledTask.deadlineNanos() <= nanoTime(); //yangyc 判断该任务是否到达可执行的时间
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {  //yangyc 提交定时任务 --- Runnable
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        validateScheduled0(delay, unit);

        return schedule(new ScheduledFutureTask<Void>(
                this,
                command,
                deadlineNanos(unit.toNanos(delay))));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {  //yangyc 提交定时任务 --- callable
        ObjectUtil.checkNotNull(callable, "callable");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        validateScheduled0(delay, unit);

        return schedule(new ScheduledFutureTask<V>(this, callable, deadlineNanos(unit.toNanos(delay))));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (period <= 0) {
            throw new IllegalArgumentException(
                    String.format("period: %d (expected: > 0)", period));
        }
        validateScheduled0(initialDelay, unit);
        validateScheduled0(period, unit);

        return schedule(new ScheduledFutureTask<Void>(
                this, command, deadlineNanos(unit.toNanos(initialDelay)), unit.toNanos(period)));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (delay <= 0) {
            throw new IllegalArgumentException(
                    String.format("delay: %d (expected: > 0)", delay));
        }

        validateScheduled0(initialDelay, unit);
        validateScheduled0(delay, unit);

        return schedule(new ScheduledFutureTask<Void>(
                this, command, deadlineNanos(unit.toNanos(initialDelay)), -unit.toNanos(delay)));
    }

    @SuppressWarnings("deprecation")
    private void validateScheduled0(long amount, TimeUnit unit) {
        validateScheduled(amount, unit);
    }

    /**
     * Sub-classes may override this to restrict the maximal amount of time someone can use to schedule a task.
     *
     * @deprecated will be removed in the future.
     */
    @Deprecated
    protected void validateScheduled(long amount, TimeUnit unit) {
        // NOOP
    }

    final void scheduleFromEventLoop(final ScheduledFutureTask<?> task) {
        // nextTaskId a long and so there is no chance it will overflow back to 0
        scheduledTaskQueue().add(task.setId(++nextTaskId));
    }

    private <V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) { //yangyc 提交定时任务 --- task
        if (inEventLoop()) {
            scheduleFromEventLoop(task);  //yangyc 添加到定时任务队列
        } else {
            final long deadlineNanos = task.deadlineNanos();
            // task will add itself to scheduled task queue when run if not expired
            if (beforeScheduledTaskSubmitted(deadlineNanos)) {
                execute(task); //yangyc 通过 EventLoop 的线程，添加到定时任务队列
            } else {
                lazyExecute(task);
                // Second hook after scheduling to facilitate race-avoidance
                if (afterScheduledTaskSubmitted(deadlineNanos)) {
                    execute(WAKEUP_TASK); //yangyc 通过 EventLoop 的线程，添加到定时任务队列
                }
            }
        }

        return task;
    }

    final void removeScheduled(final ScheduledFutureTask<?> task) { //yangyc 移除出定时任务队列
        assert task.isCancelled();
        if (inEventLoop()) {
            scheduledTaskQueue().removeTyped(task); //yangyc 移除出定时任务队列
        } else {
            // task will remove itself from scheduled task queue when it runs
            lazyExecute(task); //yangyc 通过 EventLoop 的线程，移除出定时任务队列
        }
    }

    /**
     * Called from arbitrary non-{@link EventExecutor} threads prior to scheduled task submission.
     * Returns {@code true} if the {@link EventExecutor} thread should be woken immediately to
     * process the scheduled task (if not already awake).
     * <p>
     * If {@code false} is returned, {@link #afterScheduledTaskSubmitted(long)} will be called with
     * the same value <i>after</i> the scheduled task is enqueued, providing another opportunity
     * to wake the {@link EventExecutor} thread if required.
     *
     * @param deadlineNanos deadline of the to-be-scheduled task
     *     relative to {@link AbstractScheduledEventExecutor#nanoTime()}
     * @return {@code true} if the {@link EventExecutor} thread should be woken, {@code false} otherwise
     */
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }

    /**
     * See {@link #beforeScheduledTaskSubmitted(long)}. Called only after that method returns false.
     *
     * @param deadlineNanos relative to {@link AbstractScheduledEventExecutor#nanoTime()}
     * @return  {@code true} if the {@link EventExecutor} thread should be woken, {@code false} otherwise
     */
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }
}
