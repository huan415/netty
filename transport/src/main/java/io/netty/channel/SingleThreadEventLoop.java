/*
 * Copyright 2012 The Netty Project
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

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;

import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoop}s that execute all its submitted tasks in a single thread.
 *
 */
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop { //yangyc 基于单线程的 EventLoop 抽象类，主要增加了 Channel 注册到 EventLoop 上

    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE)); //yangyc 默认任务队列最大数量

    private final Queue<Runnable> tailTasks; //yangyc 尾部任务队列，执行在 taskQueue 之后

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, threadFactory, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, Queue<Runnable> taskQueue, Queue<Runnable> tailTaskQueue,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, taskQueue, rejectedExecutionHandler); //yangyc 参数1：NioEventLoopGroup,参数2：ThreadPerTaskExecutor每个任务的线程执行器(创建线程并执行)，参数3:addTaskWakesUp，参数4：一个queue实例，参数5:拒绝策略
        tailTasks = ObjectUtil.checkNotNull(tailTaskQueue, "tailTaskQueue");
    }

    @Override
    public EventLoopGroup parent() { //yangyc 所属 EventLoopGroup --- 覆盖父类方法
        return (EventLoopGroup) super.parent();
    }

    @Override
    public EventLoop next() { //yangyc 获得自己
        return (EventLoop) super.next();
    }

    @Override
    public ChannelFuture register(Channel channel) { //yangyc 注册 Channel 到 EventLoop 上
        return register(new DefaultChannelPromise(channel, this)); //yangyc DefaultChannelPromise 类似于Future, 支持添加监听器，当关联事件完成之后，回调监听者
    }

    @Override
    public ChannelFuture register(final ChannelPromise promise) { //yangyc 注册 Channel 到 EventLoop 上
        ObjectUtil.checkNotNull(promise, "promise"); //yangyc 服务端：channel()=>NioServerSocketChannel,unsafe()=>NioMessageUnsafe,最后调用 NioMessageUnsafe#register()       客户端：channel()=>NioSocketChannel,unsafe()=>NioByteUnsafe,最后调用 NioByteUnsafe#register()
        promise.channel().unsafe().register(this, promise); //yangyc  参数1:NioEventLoop单线程线程池，参数2:promise结果封装...外部可以注册监听进行操作； 返回 ChannelPromise 对象
        return promise;
    }

    @Deprecated
    @Override
    public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        ObjectUtil.checkNotNull(channel, "channel");
        channel.unsafe().register(this, promise);
        return promise;
    }

    /**
     * Adds a task to be run once at the end of next (or current) {@code eventloop} iteration.
     *
     * @param task to be added.
     */
    @UnstableApi
    public final void executeAfterEventLoopIteration(Runnable task) { //yangyc 执行一个任务
        ObjectUtil.checkNotNull(task, "task");
        if (isShutdown()) { //yangyc 关闭时，拒绝任务
            reject();
        }

        if (!tailTasks.offer(task)) { //yangyc 添加到任务队列
            reject(task);  //yangyc 添加失败，则拒绝任务
        }

        if (!(task instanceof LazyRunnable) && wakesUpForTask(task)) { //yangyc SingleThreadEventLoop 重写了 #wakesUpForTask(Runnable task) 方法
            wakeup(inEventLoop()); //yangyc 唤醒线程
        }
    }

    /**
     * Removes a task that was added previously via {@link #executeAfterEventLoopIteration(Runnable)}.
     *
     * @param task to be removed.
     *
     * @return {@code true} if the task was removed as a result of this call.
     */
    @UnstableApi
    final boolean removeAfterEventLoopIterationTask(Runnable task) { //yangyc 移除指定任务
        return tailTasks.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    @Override
    protected void afterRunningAllTasks() { //yangyc 在运行完所有任务后，执行 tailTasks 队列中的任务
        runAllTasksFrom(tailTasks); //yangyc 执行 tailTasks 队列中的所有任务
    }

    @Override
    protected boolean hasTasks() { //yangyc 队列中是否有任务
        return super.hasTasks() || !tailTasks.isEmpty();
    }

    @Override
    public int pendingTasks() { //yangyc 待执行的任务数量 --- 两个队列的任务之和
        return super.pendingTasks() + tailTasks.size();
    }

    /**
     * Returns the number of {@link Channel}s registered with this {@link EventLoop} or {@code -1}
     * if operation is not supported. The returned value is not guaranteed to be exact accurate and
     * should be viewed as a best effort.
     */
    @UnstableApi
    public int registeredChannels() {
        return -1;
    }
}
