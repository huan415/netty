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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 * yangyc 基于单线程的 EventExecutor 抽象类，即一个 EventExecutor 对应一个线程
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor { //yangyc 继承支持定时任务的 EventExecutor；实现：表示该执行器会有序 / 串行的方式执行

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    private static final int ST_NOT_STARTED = 1; //yangyc 未开始
    private static final int ST_STARTED = 2; //yangyc 已开始
    private static final int ST_SHUTTING_DOWN = 3; //yangyc 正在关闭中
    private static final int ST_SHUTDOWN = 4; //yangyc 已关闭
    private static final int ST_TERMINATED = 5; //yangyc 已经终止

    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state"); //yangyc 字段的原子更新器
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties"); //yangyc 字段的原子更新器

    private final Queue<Runnable> taskQueue; //yangyc 任务队列

    private volatile Thread thread; //yangyc 线程
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties; //yangyc 线程属性
    private final Executor executor; //yangyc 执行器，通过它创建 thread 线程
    private volatile boolean interrupted; //yangyc 线程是否已经打断

    private final CountDownLatch threadLock = new CountDownLatch(1);
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    private final boolean addTaskWakesUp; //yangyc 添加任务到 taskQueue 队列时，是否唤醒 thread 线程
    private final int maxPendingTasks; //yangyc 最大等待队列大小，即 taskQueue 的队列大小
    private final RejectedExecutionHandler rejectedExecutionHandler; //yangyc 拒绝策略---超过taskQueue最大等待队列

    private long lastExecutionTime; //yangyc 最后执行时间

    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED; //yangyc 线程状态

    private volatile long gracefulShutdownQuietPeriod; //yangyc 优雅关闭
    private volatile long gracefulShutdownTimeout; //yangyc 优雅关闭超时时间，单位：毫秒
    private long gracefulShutdownStartTime; //yangyc 优雅关闭开始时间，单位：毫秒

    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        this.executor = ThreadExecutorMap.apply(executor, this);
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, //yangyc 参数1：NioEventLoopGroup,参数2：ThreadPerTaskExecutor每个任务的线程执行器(创建线程并执行)，参数3:addTaskWakesUp，参数4：一个queue实例，参数5:拒绝策略
                                        boolean addTaskWakesUp, Queue<Runnable> taskQueue,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = DEFAULT_MAX_PENDING_EXECUTOR_TASKS;
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = ObjectUtil.checkNotNull(taskQueue, "taskQueue");
        this.rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) { //yangyc 创建任务队列
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks); //yangyc 方法默认返回的是 LinkedBlockingQueue 阻塞队列
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
        Thread currentThread = thread;
        if (currentThread == null) { //yangyc 线程不存在，则标记线程被打断
            interrupted = true; //yangyc 线程不存在，则标记线程被打断
        } else {
            currentThread.interrupt(); //yangyc EventLoop 的线程是延迟启动，所以可能 thread 并未创建，此时通过 interrupted 标记打断。之后在 #startThread() 方法中，创建完线程后，再进行打断，也就是说，“延迟打断”
        }
    }

    /**
     * @see Queue#poll()
     */
    protected Runnable pollTask() { //yangyc 获得队头的任务
        assert inEventLoop();
        return pollTaskFrom(taskQueue); //yangyc 获得队头的任务
    }

    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) { //yangyc 获得队头的任务
        for (;;) {
            Runnable task = taskQueue.poll(); //yangyc 获得并移除队首元素。如果获得不到，返回 null
            if (task != WAKEUP_TASK) {  //yangyc 忽略 WAKEUP_TASK 任务，因为是空任务
                return task;
            }
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    private boolean fetchFromScheduledTaskQueue() { //yangyc 将定时任务队列从 scheduledTaskQueue 添加到任务队列 taskQueue, 这样定时任务可以被执行
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return true;
        }
        long nanoTime = AbstractScheduledEventExecutor.nanoTime(); //yangyc 获得当前时间
        for (;;) {
            Runnable scheduledTask = pollScheduledTask(nanoTime); //yangyc 获得指定时间内，定时任务队列首个可执行的任务（如果还没有到时间直接返回null），并且从队列中移除。
            if (scheduledTask == null) {
                return true;  //yangyc 返回 true ，表示获取完所有可执行的定时任务
            }
            if (!taskQueue.offer(scheduledTask)) { //yangyc 将定时任务添加到 taskQueue 中。若添加失败，则结束循环，返回 false ，表示未获取完所有课执行的定时任务
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                scheduledTaskQueue.add((ScheduledFutureTask<?>) scheduledTask);  //yangyc 普通队列满了加不进去，将定时任务添加回 scheduledTaskQueue 中
                return false;
            }
        }
    }

    /**
     * @return {@code true} if at least one scheduled task was executed.
     */
    private boolean executeExpiredScheduledTasks() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return false;
        }
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        if (scheduledTask == null) {
            return false;
        }
        do {
            safeExecute(scheduledTask);
        } while ((scheduledTask = pollScheduledTask(nanoTime)) != null);
        return true;
    }

    /**
     * @see Queue#peek()
     */
    protected Runnable peekTask() { //yangyc 返回队头的任务，但是不移除
        assert inEventLoop();  //yangyc 仅允许在 EventLoop 线程中执行
        return taskQueue.peek();
    }

    /**
     * @see Queue#isEmpty()
     */
    protected boolean hasTasks() { //yangyc 队列中是否有任务
        assert inEventLoop(); //yangyc 仅允许在 EventLoop 线程中执行
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     */
    public int pendingTasks() { //yangyc 获得队列中的任务数
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    protected void addTask(Runnable task) { //yangyc-main 将 task 线程放入 taskQueue 异步执行
        ObjectUtil.checkNotNull(task, "task");
        if (!offerTask(task)) { //yangyc 添加任务到队列
            reject(task);  //yangyc 添加失败，则拒绝任务
        }
    }

    final boolean offerTask(Runnable task) { //yangyc-main 将 task 线程放入 taskQueue 异步执行, 若添加失败，则返回 false
        if (isShutdown()) {
            reject(); //yangyc 关闭时，拒绝任务
        }
        return taskQueue.offer(task); //yangyc-main 将 task 线程放入 taskQueue 异步执行, 后面执行 AbstractChannel#register() 里的 register0(promise)
    }

    /**
     * @see Queue#remove(Object)
     */
    protected boolean removeTask(Runnable task) { //yangyc 移除指定任务
        return taskQueue.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    protected boolean runAllTasks() {
        assert inEventLoop();
        boolean fetchedAll;
        boolean ranAtLeastOne = false; //yangyc 是否执行过任务

        do { //yangyc fetchedAll 表示需要被调度的任务有没有转移完，没有转移完循环转移
            fetchedAll = fetchFromScheduledTaskQueue(); //yangyc 将定时任务队列从 scheduledTaskQueue 添加到任务队列 taskQueue, 这样定时任务可以被执行
            if (runAllTasksFrom(taskQueue)) {  //yangyc 执行任务队列中的所有任务
                ranAtLeastOne = true;  //yangyc 若有任务执行，则标记为 true
            }
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.
        //yangyc 此时，需要调度的任务和普通任务都已经执行完了
        if (ranAtLeastOne) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();  //yangyc 如果执行过任务，则设置最后执行时间
        }
        afterRunningAllTasks();  //yangyc 执行所有任务完成的后续方法
        return ranAtLeastOne;
    }

    /**
     * Execute all expired scheduled tasks and all current tasks in the executor queue until both queues are empty,
     * or {@code maxDrainAttempts} has been exceeded.
     * @param maxDrainAttempts The maximum amount of times this method attempts to drain from queues. This is to prevent
     *                         continuous task execution and scheduling from preventing the EventExecutor thread to
     *                         make progress and return to the selector mechanism to process inbound I/O events.
     * @return {@code true} if at least one task was run.
     */
    protected final boolean runScheduledAndExecutorTasks(final int maxDrainAttempts) {
        assert inEventLoop();
        boolean ranAtLeastOneTask;
        int drainAttempt = 0;
        do {
            // We must run the taskQueue tasks first, because the scheduled tasks from outside the EventLoop are queued
            // here because the taskQueue is thread safe and the scheduledTaskQueue is not thread safe.
            ranAtLeastOneTask = runExistingTasksFrom(taskQueue) | executeExpiredScheduledTasks();
        } while (ranAtLeastOneTask && ++drainAttempt < maxDrainAttempts);

        if (drainAttempt > 0) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        afterRunningAllTasks();

        return drainAttempt > 0;
    }

    /**
     * Runs all tasks from the passed {@code taskQueue}.
     *
     * @param taskQueue To poll and execute all tasks.
     *
     * @return {@code true} if at least one task was executed.
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) { //yangyc 执行 tailTasks 队列中的所有任务
        Runnable task = pollTaskFrom(taskQueue); //yangyc 获得队头的任务
        if (task == null) {
            return false; //yangyc 获取不到，结束执行，返回 false
        }
        for (;;) {
            safeExecute(task);   //yangyc 执行任务
            task = pollTaskFrom(taskQueue);   //yangyc 获得队头的任务
            if (task == null) {
                return true; //yangyc 获取不到，结束执行，返回 false
            }
        }
    }

    /**
     * What ever tasks are present in {@code taskQueue} when this method is invoked will be {@link Runnable#run()}.
     * @param taskQueue the task queue to drain.
     * @return {@code true} if at least {@link Runnable#run()} was called.
     */
    private boolean runExistingTasksFrom(Queue<Runnable> taskQueue) {
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        int remaining = Math.min(maxPendingTasks, taskQueue.size());
        safeExecute(task);
        // Use taskQueue.poll() directly rather than pollTaskFrom() since the latter may
        // silently consume more than one item from the queue (skips over WAKEUP_TASK instances)
        while (remaining-- > 0 && (task = taskQueue.poll()) != null) {
            safeExecute(task);
        }
        return true;
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     */
    protected boolean runAllTasks(long timeoutNanos) { //yangyc 执行所有任务, 带有超时时间
        fetchFromScheduledTaskQueue();  //yangyc 将定时任务队列从 scheduledTaskQueue 添加到任务队列 taskQueue, 这样定时任务可以被执行
        Runnable task = pollTask();   //yangyc 获得队头的任务
        if (task == null) {
            afterRunningAllTasks(); //yangyc 获取不到，结束执行，执行所有任务完成的后续方法
            return false; //yangyc 表示没有执行过任务
        }

        final long deadline = timeoutNanos > 0 ? ScheduledFutureTask.nanoTime() + timeoutNanos : 0; //yangyc 计算执行任务截止时间
        long runTasks = 0; //yangyc 已执行任务数量
        long lastExecutionTime; //yangyc 最后一个任务的执行时间
        for (;;) { //yangyc 循环执行任务
            safeExecute(task); //yangyc 执行任务

            runTasks ++; //yangyc 计数++

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            if ((runTasks & 0x3F) == 0) { //yangyc 0x3F=>63; 等于0时=>runTasks=64或128... 每隔 64 个任务检查一次时间，因为 nanoTime() 是相对费时的操作
                lastExecutionTime = ScheduledFutureTask.nanoTime(); //yangyc 重新获得时间
                if (lastExecutionTime >= deadline) {  //yangyc 超过任务截止时间，结束
                    break;
                }
            }

            task = pollTask(); //yangyc 获得队头的任务
            if (task == null) {  //yangyc 获取不到，结束执行
                lastExecutionTime = ScheduledFutureTask.nanoTime(); //yangyc 重新获得时间
                break;
            }
        }

        afterRunningAllTasks(); //yangyc 执行所有任务完成的后续方法
        this.lastExecutionTime = lastExecutionTime; //yangyc 设置最后执行时间
        return true; //yangyc 表示有执行过任务
    }

    /**
     * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
     */
    @UnstableApi
    protected void afterRunningAllTasks() { }  //yangyc 执行所有任务完成的后续方法 [SingleThreadEventLoop]里实现

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Returns the absolute point in time (relative to {@link #nanoTime()}) at which the next
     * closest scheduled task should run.
     */
    @UnstableApi
    protected long deadlineNanos() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return nanoTime() + SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.deadlineNanos();
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() { //yangyc 更新最后执行时间
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * Run the tasks in the {@link #taskQueue}
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() { //yangyc 空方法，在子类 NioEventLoop 中覆写--关闭 NIO Selector 对象。
        // NOOP
    }

    protected void wakeup(boolean inEventLoop) { //yangyc 唤醒线程---NioEventLoop 重写
        if (!inEventLoop) { //yangyc 判断不在 EventLoop 的线程中。因为，如果在 EventLoop 线程中，意味着线程就在执行中，不必要唤醒。
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            taskQueue.offer(WAKEUP_TASK); //yangyc 添加任务到队列中
        }
    }

    @Override
    public boolean inEventLoop(Thread thread) { //yangyc 判断指定线程是否是 EventLoop 线程
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) { //yangyc 该方法只是将线程状态修改为ST_SHUTTING_DOWN并不执行具体的关闭操作
        ObjectUtil.checkPositiveOrZero(quietPeriod, "quietPeriod");
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        ObjectUtil.checkNotNull(unit, "unit");

        if (isShuttingDown()) {
            return terminationFuture(); //yangyc 正在关闭阻止其他线程
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return terminationFuture(); //yangyc 正在关闭阻止其他线程
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() { //yangyc 优雅关闭
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() { //yangyc 确定是否可以关闭或者说是否可以从EventLoop循环中跳出
        if (!isShuttingDown()) {
            return false; //yangyc 没有调用shutdown相关的方法直接返回
        }

        if (!inEventLoop()) { //yangyc 必须是原生线程
            throw new IllegalStateException("must be invoked from an event loop");
        }

        cancelScheduledTasks(); //yangyc 取消调度任务

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime(); //yangyc 优雅关闭开始时间
        }

        if (runAllTasks() || runShutdownHooks()) { //yangyc 执行完普通任务, 如果没有普通任务时执行完shutdownHook任务
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true; //yangyc 调用shutdown()方法直接退出
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;  //yangyc 优雅关闭静默时间为0也直接退出
            }
            taskQueue.offer(WAKEUP_TASK);
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true; //yangyc shutdown()方法调用直接返回，优雅关闭截止时间到也返回
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            taskQueue.offer(WAKEUP_TASK);
            try {
                Thread.sleep(100);  //yangyc 在静默期间每100ms唤醒线程执行期间提交的任务
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;  //yangyc 静默时间内没有任务提交，可以优雅关闭，此时若用户又提交任务则不会被执行
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ObjectUtil.checkNotNull(unit, "unit");
        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        threadLock.await(timeout, unit);

        return isTerminated();
    }

    @Override
    public void execute(Runnable task) { //yangyc 执行一个任务
        ObjectUtil.checkNotNull(task, "task");
        execute(task, !(task instanceof LazyRunnable) && wakesUpForTask(task));
    }

    @Override
    public void lazyExecute(Runnable task) {
        execute(ObjectUtil.checkNotNull(task, "task"), false);
    }

    private void execute(Runnable task, boolean immediate) {  //yangyc-main 将 task 线程放入 taskQueue 异步执行，后面执行 AbstractChannel#register() 里的 register0(promise)
        boolean inEventLoop = inEventLoop();//yangyc 当前是否在 EventLoop 的线程中
        addTask(task); //yangyc-main 将 task 线程放入 taskQueue 异步执行
        if (!inEventLoop) {
            startThread(); //yangyc-main 开始执行线程，启动 EventLoop 独占的线程
            if (isShutdown()) {
                boolean reject = false;
                try {
                    if (removeTask(task)) { //yangyc 若已经关闭且移除任务，并进行拒绝
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }
                if (reject) {
                    reject(); //yangyc 若已经关闭且移除任务，并进行拒绝
                }
            }
        }

        if (!addTaskWakesUp && immediate) {
            wakeup(inEventLoop); //yangyc 唤醒线程
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException { //yangyc 调用 EventExecutor 执行多个普通任务，有一个执行完成即可
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks); //yangyc 调用父类 AbstractScheduledEventExecutor 的 #invokeAny(tasks, ...) 方法，执行多个普通任务，有一个执行完成即可
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) //yangyc 调用 EventExecutor 执行多个普通任务，有一个执行完成即可
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit); //yangyc 调用父类 AbstractScheduledEventExecutor 的 #invokeAny(tasks, ...) 方法，执行多个普通任务，有一个执行完成即可
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException { //yangyc 调用 EventExecutor 执行多个普通任务
        throwIfInEventLoop("invokeAll"); //yangyc 判断若在 EventLoop 的线程中调用该方法，抛出 RejectedExecutionException 异常
        return super.invokeAll(tasks); //yangyc 调用父类 AbstractScheduledEventExecutor 的 #invokeAll(tasks, ...) 方法，执行多个普通任务
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll( //yangyc 调用 EventExecutor 执行多个普通任务
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit); //yangyc 调用父类 AbstractScheduledEventExecutor 的 #invokeAll(tasks, ...) 方法，执行多个普通任务
    }

    private void throwIfInEventLoop(String method) { //yangyc 判断若在 EventLoop 的线程中调用该方法，抛出 RejectedExecutionException 异常
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until
     * it is fully started.
     */
    public final ThreadProperties threadProperties() { //yangyc 获得 EventLoop 的线程属性
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) { //yangyc 获得 ThreadProperties 对象。若不存在，则进行创建 ThreadProperties 对象
            Thread thread = this.thread;
            if (thread == null) { //yangyc 因为线程是延迟启动的，所以会出现线程为空的情况。若线程为空，则需要进行创建。
                assert !inEventLoop();
                submit(NOOP_TASK).syncUninterruptibly(); //yangyc submit: 提交空任务，促使 execute 方法执行; syncUninterruptibly():保证 execute() 方法中异步创建 thread 完成
                thread = this.thread;  //yangyc 获得线程
                assert thread != null; //yangyc 获得线程，并断言保证线程存在
            }

            threadProperties = new DefaultThreadProperties(thread); //yangyc 创建 DefaultThreadProperties 对象
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {  //yangyc CAS 修改 threadProperties 属性
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    /**
     * @deprecated use {@link AbstractEventExecutor.LazyRunnable}
     */
    @Deprecated
    protected interface NonWakeupRunnable extends LazyRunnable { } //yangyc 用于标记不唤醒线程的任务

    /**
     * Can be overridden to control which tasks require waking the {@link EventExecutor} thread
     * if it is waiting so that they can be run immediately.
     */
    protected boolean wakesUpForTask(Runnable task) { //yangyc 判断该任务是否需要唤醒线程
        return true;
    }

    protected static void reject() { //yangyc 拒绝任务
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * Offers the task to the associated {@link RejectedExecutionHandler}.
     *
     * @param task to reject.
     */
    protected final void reject(Runnable task) { //yangyc 拒绝任务
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    private void startThread() { //yangyc-main 开始执行线程
        if (state == ST_NOT_STARTED) {
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                boolean success = false;
                try {
                    doStartThread(); //yangyc-main 开始执行线程
                    success = true;
                } finally {
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED);
                    }
                }
            }
        }
    }

    private boolean ensureThreadStarted(int oldState) {
        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    private void doStartThread() { //yangyc-main 开始执行线程
        assert thread == null;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                thread = Thread.currentThread(); //yangyc 记录当前线程
                if (interrupted) { //yangyc 如果当前线程已经被标记打断，则进行打断操作。
                    thread.interrupt();
                }

                boolean success = false; //yangyc 是否执行成功
                updateLastExecutionTime();  //yangyc 更新最后执行时间
                try {
                    SingleThreadEventExecutor.this.run(); //yangyc-main 开始执行线程 [NioEventLoop]
                    success = true; //yangyc 标记执行成功
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    for (;;) { //yangyc 优雅关闭
                        int oldState = state;
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }

                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) { //yangyc 优雅关闭
                        if (logger.isErrorEnabled()) {
                            logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                    SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                    "be called before run() implementation terminates.");
                        }
                    }

                    try { //yangyc 优雅关闭
                        // Run all remaining tasks and shutdown hooks. At this point the event loop
                        // is in ST_SHUTTING_DOWN state still accepting tasks which is needed for
                        // graceful shutdown with quietPeriod.
                        for (;;) {
                            if (confirmShutdown()) {
                                break;
                            }
                        }

                        // Now we want to make sure no more tasks can be added from this point. This is
                        // achieved by switching the state. Any new tasks beyond this point will be rejected.
                        for (;;) {
                            int oldState = state;
                            if (oldState >= ST_SHUTDOWN || STATE_UPDATER.compareAndSet(
                                    SingleThreadEventExecutor.this, oldState, ST_SHUTDOWN)) {
                                break;
                            }
                        }

                        // We have the final set of tasks in the queue now, no more can be added, run all remaining.
                        // No need to loop here, this is the final pass.
                        confirmShutdown();
                    } finally {
                        try {
                            cleanup(); //yangyc 清理，释放资源
                        } finally {
                            // Lets remove all FastThreadLocals for the Thread as we are about to terminate and notify
                            // the future. The user may block on the future and once it unblocks the JVM may terminate
                            // and start unloading classes.
                            // See https://github.com/netty/netty/issues/6596.
                            FastThreadLocal.removeAll();

                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.countDown();
                            int numUserTasks = drainTasks();
                            if (numUserTasks > 0 && logger.isWarnEnabled()) {
                                logger.warn("An event executor terminated with " +
                                        "non-empty task queue (" + numUserTasks + ')');
                            }
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    final int drainTasks() {
        int numTasks = 0;
        for (;;) {
            Runnable runnable = taskQueue.poll();
            if (runnable == null) {
                break;
            }
            // WAKEUP_TASK should be just discarded as these are added internally.
            // The important bit is that we not have any user tasks left.
            if (WAKEUP_TASK != runnable) {
                numTasks++;
            }
        }
        return numTasks;
    }

    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
