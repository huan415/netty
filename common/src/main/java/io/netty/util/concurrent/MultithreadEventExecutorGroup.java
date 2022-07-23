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
package io.netty.util.concurrent;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    private final EventExecutor[] children; //yangyc EventExecutor 数组
    private final Set<EventExecutor> readonlyChildren; //yangyc 只读的 EventExecutor 数组
    private final AtomicInteger terminatedChildren = new AtomicInteger(); //yangyc 已终止的 EventExecutor 数量
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE); //yangyc 用于终止 EventExecutor 的异步 Future
    private final EventExecutorChooserFactory.EventExecutorChooser chooser; //yangyc EventExecutor 选择器

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args); //yangyc ThreadPerTaskExecutor每个任务一个线程的执行器实现类
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected  MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {//yangyc 参数1:线程数量, 参数2:执行器, args[0]:选择器提供器--获取jdk层面的Selector, args[1]: 选择器工作策略， args[2]:拒绝策略
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args); //yangyc 加了生成Chooser的工厂类
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) { //yangyc 参数1:线程数量, 参数2:执行器,参数3:生成Chooser的工厂类, args[0]:选择器提供器--获取jdk层面的Selector, args[1]: 选择器工作策略， args[2]:拒绝策略
        checkPositive(nThreads, "nThreads");

        if (executor == null) {
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory()); //yangyc 创建执行器;  newDefaultThreadFactory()==创建线程工厂对象;  executor:通过DefaultThreadFactory来创建线程并启动
        }

        children = new EventExecutor[nThreads];  //yangyc 创建 EventExecutor 数组

        for (int i = 0; i < nThreads; i ++) {
            boolean success = false; //yangyc 是否创建成功
            try {
                children[i] = newChild(executor, args); //yangyc 创建 EventExecutor 对象, 实际是NioEventLoop对象。参数1：ThreadPerTaskExecutor每个任务的线程执行器(创建线程并执行)， args[0]:选择器提供器--获取jdk层面的Selector, args[1]: 选择器工作策略， args[2]:拒绝策略
                success = true; //yangyc 标记创建成功
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e); //yangyc 创建失败，抛出异常
            } finally {
                if (!success) {
                    for (int j = 0; j < i; j ++) { //yangyc 创建失败，关闭所有已创建的 EventExecutor
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j ++) { //yangyc 确保所有已创建的 EventExecutor 已关闭
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        chooser = chooserFactory.newChooser(children); //yangyc chooser工厂类创建 EventExecutor 选择器，有两种（根据children数组大小）：PowerOfTwoEventExecutorChooser和GenericEventExecutorChooser。外部获取或注册到NioEventLoop，都是通过chooser分配NioEventLoop

        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception { //yangyc 创建结束监听器，用于 EventExecutor（NioEventLoop） 终止时的监听
                if (terminatedChildren.incrementAndGet() == children.length) { //yangyc 全部关闭
                    terminationFuture.setSuccess(null); //yangyc 设置结果，并通知监听器们
                }
            }
        };

        for (EventExecutor e: children) { //yangyc 每个 EventExecutor（NioEventLoop） 都设置监听器
            e.terminationFuture().addListener(terminationListener); //yangyc 每个 EventExecutor（NioEventLoop） 结束调用FutureListener#operationComplete()-->terminatedChildren自增直到等于children.length
        }

        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet);  //yangyc 创建只读的 EventExecutor 数组
    }

    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass()); //yangyc 创建线程工厂对象。线程工厂命名规则: className+pollId; 通过该工厂创建的线程(FastThreadLocalThread)命名规则: className+pollId+nextId
    }

    @Override
    public EventExecutor next() {
        return chooser.next(); //yangyc-main 选择下一个 EventExecutor 对象
    }

    @Override
    public Iterator<EventExecutor> iterator() { //yangyc 获得 EventExecutor 数组的迭代器
        return readonlyChildren.iterator(); //yangyc 只读数组 ---- 避免调用方，获得迭代器后，对 EventExecutor 数组进行修改
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() { //yangyc 获得 EventExecutor 数组的大小
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;  //yangyc 创建 EventExecutor 对象

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
