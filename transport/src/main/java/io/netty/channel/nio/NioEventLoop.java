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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop { //yangyc NIO EventLoop 实现类，实现对注册到其中的 Channel 的就绪的 IO 事件，和对用户提交的任务进行处理

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false); //yangyc 是否禁用 SelectionKey 的优化，默认开启

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3; //yangyc 少于该值，不开启空轮询重建新的 Selector 对象的功能
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD; //yangyc NIO Selector 空轮询该 N 次后，重建新的 Selector 对象 --- 用以解决 JDK NIO 的 epoll 空轮询 Bug

    private final IntSupplier selectNowSupplier = new IntSupplier() { //yangyc 返回当前 Channel 新增的 IO 就绪事件的数量
        @Override
        public int get() throws Exception {
            return selectNow(); //yangyc 调用多路复用器的 selectNow() 方法，该方法不阻塞，返回当前 Channel 新增的 IO 就绪事件的数量
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.openjdk.java.net/browse/JDK-6427854 for first few dev (unreleased) builds of JDK 7
    // - https://bugs.openjdk.java.net/browse/JDK-6527572 for JDK prior to 5.0u15-rev and 6u10
    // - https://github.com/netty/netty/issues/203
    static {
        if (PlatformDependent.javaVersion() < 7) {
            final String key = "sun.nio.ch.bugLevel";
            final String bugLevel = SystemPropertyUtil.get(key);
            if (bugLevel == null) {
                try {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                        @Override
                        public Void run() {
                            System.setProperty(key, "");
                            return null;
                        }
                    });
                } catch (final SecurityException e) {
                    logger.debug("Unable to get/set System Property: " + key, e);
                }
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector; //yangyc 包装的 NIO Selector 对象，Netty 对 NIO Selector 做了优化
    private Selector unwrappedSelector; //yangyc 未包装的 NIO Selector 对象
    private SelectedSelectionKeySet selectedKeys; //yangyc 当前 NIOEventLoop 的 selector 就绪事件集合

    private final SelectorProvider provider; //yangyc SelectorProvider 对象，用于创建 Selector 对象

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    private final SelectStrategy selectStrategy; //yangyc Select 策略

    private volatile int ioRatio = 50; //yangyc 处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例
    private int cancelledKeys; //yangyc 取消 SelectionKey 的数量
    private boolean needsToSelectAgain; //yangyc 是否需要再次 select Selector 对象

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider, //yangyc 参数1：NioEventLoopGroup,参数2：ThreadPerTaskExecutor每个任务的线程执行器(创建线程并执行)，参数3:选择器提供器--获取jdk层面的Selector, 参数4: 选择器工作策略，参数5:拒绝策略，参数6：taskQueueFactory，参数7：tailTaskQueueFactory
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory, EventLoopTaskQueueFactory tailTaskQueueFactory) {
        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory),
                rejectedExecutionHandler);//yangyc 参数1：NioEventLoopGroup,参数2：ThreadPerTaskExecutor每个任务的线程执行器(创建线程并执行)，参数3:addTaskWakesUp，参数4：一个queue实例，参数5：一个queue实例，参数6:拒绝策略
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        final SelectorTuple selectorTuple = openSelector(); //yangyc 创建 NIO Selector 对象， 每个NioEventLoop都有一个selector对象
        this.selector = selectorTuple.selector; //yangyc 包装后的 Selector 对象
        this.unwrappedSelector = selectorTuple.unwrappedSelector; //yangyc 未包装后的 Selector 对象
    }

    private static Queue<Runnable> newTaskQueue( //yangyc 创建任务队列 --- 覆写父类的方法
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple { //yangyc NioEventLoop 内部类
        final Selector unwrappedSelector; //yangyc 未包装的 Selector 对象
        final Selector selector; //yangyc 已包装的 Selector 对象

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() { //yangyc 创建 Selector 对象
        final Selector unwrappedSelector; //yangyc 创建 Selector 对象，作为 unwrappedSelector
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEY_SET_OPTIMIZATION) { //yangyc 禁用 SelectionKey 的优化，则直接返回 SelectorTuple 对象。即，selector 使用 unwrappedSelector 。
            return new SelectorTuple(unwrappedSelector);
        }

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() { //yangyc 获得 SelectorImpl 类
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader()); //yangyc 成功，则返回该类
                } catch (Throwable cause) {
                    return cause; //yangyc 失败，则返回该异常
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) || //yangyc 获得 SelectorImpl 类失败，则直接返回 SelectorTuple 对象。即，selector 使用 unwrappedSelector
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet(); //yangyc 创建 SelectedSelectionKeySet 对象

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {  //yangyc 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true); //yangyc 设置 Field 可访问
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);  //yangyc 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 的 Field 中
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e; //yangyc 失败，则返回该异常
                } catch (IllegalAccessException e) {
                    return e; //yangyc 失败，则返回该异常
                }
            }
        });
        //yangyc 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中失败，则直接返回 SelectorTuple 对象。即，selector 使用 unwrappedSelector
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet; //yangyc 设置 SelectedSelectionKeySet 对象到 selectedKeys 中
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector, //yangyc 创建 SelectorTuple 对象。即，selector 也使用 SelectedSelectionKeySetSelector 对象
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()  yangyc 创建 mpsc 队列 --- multiple producers and a single consumer。多线程生产任务，单线程消费任务的消费
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) { //yangyc 注册 Java NIO Channel 到 Selector(EventLoop) 上,
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task); //yangyc SelectableChannel#register()， 注册 Java NIO Channel 到 Selector 上
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync(); //yangyc SelectableChannel#register()， 注册 Java NIO Channel 到 Selector 上
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task); //yangyc SelectableChannel#register()， 注册 Java NIO Channel 到 Selector 上
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) { //yangyc 设置 ioRatio 属性
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() { //yangyc 重建 NIO Selector 对象
        if (!inEventLoop()) {   //yangyc 只允许在 EventLoop 的线程中执行
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0(); //yangyc 重建 Selector 对象。
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    private void rebuildSelector0() { //yangyc 重建 NIO Selector 对象
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple; //yangyc 创建新的 Selector 对象

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector. //yangyc 将注册在 NioEventLoop 上的所有 Channel ，注册到新创建 Selector 对象上
        int nChannels = 0; //yangyc 计算重新注册成功的 Channel 数量
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel(); //yangyc 取消老的 SelectionKey
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a); //yangyc 将 Channel 注册到新的 Selector 对象上
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey; //yangyc 修改 Channel 的 selectionKey 指向新的 SelectionKey 上
                }
                nChannels ++; // 计数 ++
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise()); //yangyc 关闭发生异常的 Channel
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector; //yangyc 修改 selector 指向新的 Selector 对象
        unwrappedSelector = newSelectorTuple.unwrappedSelector;  //yangyc 修改 unwrappedSelector 指向新的 Selector 对象

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close(); //yangyc 关闭老的 Selector 对象
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        int selectCnt = 0;
        for (;;) { //yangyc-main 死循环执行监听 IO 事件
            try {
                int strategy; //yangyc 1.>=0: 表示 selector 的返回值, 注册在多路复用器上就绪的个数，2.<0: 常量状态, CONTINUE、BUSY_WAIT、SELECT
                try { //yangyc selectStrategy: DefaultSelectStrategy 对象
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks()); //yangyc 当前NioEventLoop 是否有本地任务，有任务：调用多路复用器的selectNow()返回就绪个数；没有任务：返回-1
                    switch (strategy) {
                    case SelectStrategy.CONTINUE: //yangyc 默认实现下，不存在这个情况
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT: //yangyc 没有任务返回-1，处理-1响应的逻辑
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos(); //yangyc 获取可调度任务的截止事件
                        if (curDeadlineNanos == -1L) { //yangyc -1:表示 EventLoop 内没有需要周期性调度的任务
                            curDeadlineNanos = NONE; // nothing on the calendar yangyc Long.MAX_VALUE
                        }
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            if (!hasTasks()) { //yangyc 没有本地普通任务需要执行。curDeadlineNanos：1.long最大值：说明没有周期性任务；2：不是long最大值，表示周期性任务需要执行的截止时间
                                strategy = select(curDeadlineNanos); //yangyc-main 进行 Selector 阻塞 select,返回就绪 ch 事件个数, 当 timeoutMillis 超时或者有事件发生会break
                            }
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio; //yangyc 线程处理 IO 事件的事件占比，默认50%
                boolean ranTasks; //yangyc 表示本轮线程有没有处理过本地任务
                if (ioRatio == 100) { //yangyc 条件成立，表示 IO 优先， IO 处理完之后，再处理本地任务
                    try {
                        if (strategy > 0) { //yangyc 条件成立，表示当前 NioEventLoop 内的 selector 上有就绪的事件
                            processSelectedKeys(); //yangyc-main 处理 Channel 感兴趣的就绪 IO 事件
                        }
                    } finally {
                        // Ensure we always run tasks.
                        ranTasks = runAllTasks(); //yangyc-main 运行所有普通任务和定时任务，不限制时间
                    }
                } else if (strategy > 0) { //yangyc 条件成立，表示当前 NioEventLoop 内的 selector 上有就绪的事件
                    final long ioStartTime = System.nanoTime(); //yangyc IO 事件处理的开始事件
                    try {
                        processSelectedKeys(); //yangyc-main 处理 Channel 感兴趣的就绪 IO 事件
                    } finally {
                        // Ensure we always run tasks.
                        final long ioTime = System.nanoTime() - ioStartTime; //yangyc IO事件处理总耗时；[ioTime * (100 - ioRatio) / ioRatio)] 根据 IO 处理时间，计算出一个执行本地队列任务的最大时间
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio); //yangyc-main 运行所有普通任务和定时任务，限制时间
                    }
                } else { //yangyc 条件成立，表示当前 NioEventLoop 内的 selector 上没有就绪的事件，只处理本地任务
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks yangyc 0：表示执行最少数量(64)的本地任务
                }

                if (ranTasks || strategy > 0) {
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    selectCnt = 0; //yangyc 正常 NioEventLoop 线程从 selector 多路复用器上唤醒后，因为有IO时间，会把 selectCnt 置为0
                } else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case) //yangyc-main 经典：Epoll有一个bug（selector.select()没有阻塞马上返回）,如果没有这一步，由于没有就绪事件=>strategy=-1=>死循环selector.select()=>CPU占满
                    selectCnt = 0; //yangyc-main 经典：unexpectedSelectorWakeup(selectCnt)中，通过 selectCnt 计数，达到阈值，重置 selector
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    if (isShuttingDown()) {
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 && //yangyc 不符合 select 超时的提交 且 select 次数到达重建 Selector 对象的上限
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            rebuildSelector(); //yangyc 重建 Selector 对象
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() { //yangyc-main 处理 Channel 感兴趣的就绪 IO 事件
        if (selectedKeys != null) { //yangyc 当 selectedKeys 非空，意味着使用优化的 SelectedSelectionKeySetSelector ，所以调用 #processSelectedKeysOptimized() 方法
            processSelectedKeysOptimized(); //yangyc-main for循环处理 Channel 感兴趣的就绪 IO 事件
        } else {
            processSelectedKeysPlain(selector.selectedKeys()); //yangyc 基于 Java NIO 原生 Selecotr ，处理 Channel 新增就绪的 IO 事件
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) { //yangyc 基于 Java NIO 原生 Selecotr ，处理 Channel 新增就绪的 IO 事件
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator(); //yangyc 遍历 SelectionKey 迭代器
        for (;;) {
            final SelectionKey k = i.next();  //yangyc 获得 SelectionKey 对象
            final Object a = k.attachment(); //yangyc 获得 SelectionKey 对象
            i.remove(); //yangyc 从迭代器中移除

            if (a instanceof AbstractNioChannel) {  //yangyc 当 attachment 是 Netty NIO Channel 时
                processSelectedKey(k, (AbstractNioChannel) a); //yangyc 处理一个 Channel 就绪的 IO 事件
            } else { //yangyc 当 attachment 是 Netty NioTask 时
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task); //yangyc 处理一个 Channel 就绪的 IO 事件
            }

            if (!i.hasNext()) {
                break; //yangyc 无下一个节点，结束
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() { //yangyc-main for循环处理 Channel 感兴趣的就绪 IO 事件
        for (int i = 0; i < selectedKeys.size; ++i) { //yangyc 遍历就绪事件集合（selectedKeys）数组
            final SelectionKey k = selectedKeys.keys[i]; //yangyc 表示就绪事件
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            final Object a = k.attachment(); //yangyc 拿到就绪事件附件--注册时阶段向 selector 提供的 channel 事件。可能是：NioServerSocketChannel 或 NioSocketChannel

            if (a instanceof AbstractNioChannel) { //yangyc 处理一个 Channel 就绪的 IO 事件
                processSelectedKey(k, (AbstractNioChannel) a); //yangyc-main 大部分情况走这个，处理 Channel 感兴趣的就绪 IO 事件
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) { //yangyc-main 处理 Channel 感兴趣的就绪 IO 事件
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe(); //yangyc 两种情况：1.NioServerSocketChannel=>NioMessageUnSafe; 2.NioSocketChannel=>NioByteUnsafe
        if (!k.isValid()) { //yangyc 如果 SelectionKey 是不合法的，则关闭 Channel
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            int readyOps = k.readyOps(); //yangyc 获得就绪的 IO 事件的 ops
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {  //yangyc OP_CONNECT 事件就绪
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps(); //yangyc 移除对 OP_CONNECT 感兴趣，即不再监听连接事件
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect(); //yangyc 完成连接
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) { //yangyc OP_WRITE 事件就绪
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();  //yangyc 向 Channel 写入数据, 在完成写入数据后，会移除对 OP_WRITE 的感兴趣
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop //yangyc 当 (readyOps & SelectionKey.OP_ACCEPT) != 0, EventLoop 线程轮询到 channel 上有可读或可写的事件
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {  //yangyc readyOps == 0 是对 JDK Bug 的处理，防止空的死循环。通过 Unsafe.read() 再次将当前 channel 的事件列表设置为监听读
                unsafe.read(); //yangyc-main [AbstractNioChannel.NioUnsafe] 两种情况：1.服务端：NioMessageUnsafe#read=>创建客户端SocketChannel; 2.客户端：SocketChannel=>NioByteUnSafe.read() 读取缓冲区的数据，并且讲数据响应到 pipeline 中
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise()); //yangyc 发生异常，关闭 Channel
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) { //yangyc 使用 NioTask ，自定义实现 Channel 处理 Channel IO 就绪的事件
        int state = 0; //yangyc 未执行
        try {
            task.channelReady(k.channel(), k); //yangyc 调用 NioTask 的 Channel 就绪事件
            state = 1; //yangyc 执行成功
        } catch (Exception e) {
            k.cancel(); //yangyc SelectionKey 取消
            invokeChannelUnregistered(task, k, e);  //yangyc 执行 Channel 取消注册
            state = 2; //yangyc 执行异常
        } finally {
            switch (state) {
            case 0:
                k.cancel();  //yangyc SelectionKey 取消
                invokeChannelUnregistered(task, k, null); //yangyc 执行 Channel 取消注册
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);  //yangyc SelectionKey 不合法，则执行 Channel 取消注册
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) { //yangyc NioTask#channelUnregistered() 方法，执行 Channel 取消注册
        try {
            task.channelUnregistered(k.channel(), cause); //yangyc NioTask#channelUnregistered() 方法，执行 Channel 取消注册
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) { //yangyc 唤醒线程, 因为 NioEventLoop 的线程阻塞 --- 因为Selector#select(long timeout) 方法，阻塞等待有 Channel 感兴趣的 IO 事件，所以需要调用 Selector#wakeup() 方法，进行唤醒 Selector
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            selector.wakeup(); //yangyc 唤醒操作开销比较大，并且每次重复调用相当于重复唤醒。所以，通过 CAS 修改 wakenUp 属性 false => true ，保证有且仅有进行一次唤醒
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException { //yangyc 返回当前 Channel 新增的 IO 就绪事件的数量
        return selector.selectNow();
    }

    private int select(long deadlineNanos) throws IOException { //yangyc-main 死循环执行监听 IO 事件； curDeadlineNanos：1.long最大值：说明没有周期性任务；2：不是long最大值，表示周期性任务需要执行的截止时间
        if (deadlineNanos == NONE) { //yangyc long最大值：说明没有周期性任务
            return selector.select(); //yangyc 阻塞当前调用线程，直到有就绪的ch, 再返回就绪事件的个数
        }
        // Timeout will only be 0 if deadline is within 5 microsecs yangyc 不是long最大值，表示周期性任务需要执行的截止时间
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L; //yangyc 截止时间转换成延期时间
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis); //yangyc-main 当 timeoutMillis 超时或者有事件发生会break
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
