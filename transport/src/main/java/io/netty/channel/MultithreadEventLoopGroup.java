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

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoopGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventLoopGroup extends MultithreadEventExecutorGroup implements EventLoopGroup {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MultithreadEventLoopGroup.class);

    private static final int DEFAULT_EVENT_LOOP_THREADS; //yangyc 默认 EventLoop 线程数 ---- 一个 EventLoop 对应一个线程，所以为 CPU 数量 * 2

    static {
        DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.eventLoopThreads: {}", DEFAULT_EVENT_LOOP_THREADS);
        }
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, Object... args) {//yangyc 参数1:线程数量, 参数2:执行器, 参数3(args[0]):选择器提供器--获取jdk层面的Selector, 参数4(args[1]): 选择器工作策略， 参数5(args[2]):拒绝策略
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, args); //yangyc 线程数==0，修改为配置（io.netty.eventLoopThreads）或核心数*2
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, ThreadFactory, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, threadFactory, args);
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor,
     * EventExecutorChooserFactory, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                                     Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, chooserFactory, args);
    }

    @Override
    protected ThreadFactory newDefaultThreadFactory() { //yangyc 创建线程工厂对象
        return new DefaultThreadFactory(getClass(), Thread.MAX_PRIORITY); //yangyc 线程优先级为 Thread.MAX_PRIORITY
    }

    @Override
    public EventLoop next() { //yangyc-main 选择下一个 EventLoop 对象
        return (EventLoop) super.next();
    }

    @Override
    protected abstract EventLoop newChild(Executor executor, Object... args) throws Exception; //yangyc 创建 EventExecutor 对象

    @Override
    public ChannelFuture register(Channel channel) { //yangyc-main 从 boosGroup 里拿一个线程来处理 Channel，并将其注册到自己的 Selector
        return next().register(channel); //yangyc next()=>从当前的 group 里选择应该 NioServerSocketChannel/NioSocketChannel 对象返回； NioServerSocketChannel/NioSocketChannel 是一个单线程池，并且内部有一个 selector。 怎么选呢？PowerOfTwoEventExecutorChooser或GenericEventExecutorChooser
    }

    @Override
    public ChannelFuture register(ChannelPromise promise) { //yangyc 注册 Channel 到 EventLoopGroup 中。实际上，EventLoopGroup 会分配一个 EventLoop 给该 Channel 注册
        return next().register(promise);
    }

    @Deprecated
    @Override
    public ChannelFuture register(Channel channel, ChannelPromise promise) { //yangyc 注册 Channel 到 EventLoopGroup 中。实际上，EventLoopGroup 会分配一个 EventLoop 给该 Channel 注册
        return next().register(channel, promise);
    }

}
