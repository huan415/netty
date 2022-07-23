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

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    private final Channel parent; //yangyc 父 Channel 对象，其中对于 NioServerSocketChannel 的 parent 为空
    private final ChannelId id; //yangyc Channel 编号
    private final Unsafe unsafe; //yangyc Unsafe 对象 ---- Channel 真正的具体操作，都是通过调用对应的 Unsafe 实现
    private final DefaultChannelPipeline pipeline; //yangyc DefaultChannelPipeline 对象
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);
    private final CloseFuture closeFuture = new CloseFuture(this);

    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;
    private volatile EventLoop eventLoop;
    private volatile boolean registered; //yangyc 是否注册
    private boolean closeInitiated;
    private Throwable initialCloseCause;

    /** Cache for the string representation of this channel */
    private boolean strValActive;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent) { //yangyc-main newChannelPipeline() 初始化pipeline
        this.parent = parent;
        id = newId(); //yangyc 创建 Channel 编号 -- channelId
        unsafe = newUnsafe(); //yangyc 创建 Unsafe 对象.  服务端 NioServerSocketChannel=>NioMessageUnsafe; 客户端：NioSocketChannel=>NioByteUnsafe
        pipeline = newChannelPipeline(); //yangyc-main 创建 DefaultChannelPipeline 对象
    }

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, ChannelId id) {
        this.parent = parent;
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    protected final int maxMessagesPerWrite() {
        ChannelConfig config = config();
        if (config instanceof DefaultChannelConfig) {
            return ((DefaultChannelConfig) config).getMaxMessagesPerWrite();
        }
        Integer value = config.getOption(ChannelOption.MAX_MESSAGES_PER_WRITE);
        if (value == null) {
            return Integer.MAX_VALUE;
        }
        return value;
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    /**
     * Returns a new {@link DefaultChannelId} instance. Subclasses may override this method to assign custom
     * {@link ChannelId}s to {@link Channel}s that use the {@link AbstractChannel#AbstractChannel(Channel)} constructor.
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * Returns a new {@link DefaultChannelPipeline} instance.
     */
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this);
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) { //yangyc Outbound 事件的发起者是 Channel
        return pipeline.bind(localAddress); //yangyc 实际调用 DefaultChannelPipeline#bind()
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect(); //yangyc disconnect 事件在 pipeline 上传播
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();  //yangyc 将 close 事件在 pipeline 上传播 --- [DefaultChannelPipeline->TailContext->HeadContext]
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public Channel flush() { //yangyc 刷新内存队列，将其中的数据写入到对端，此时数据才真正写到对端
        pipeline.flush(); //yangyc 将 flush 事件在 pipeline 上传播
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {  //yangyc 将数据写到内存队列中,此时数据并没有写入到对端
        return pipeline.write(msg); //yangyc write 事件在 pipeline 上传播
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) { //yangyc 将数据写到内存队列中,此时数据并没有写入到对端
        return pipeline.write(msg, promise); //yangyc write 事件在 pipeline 上传播
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) { //yangyc write + flush 的组合，将数据写到内存队列后，立即刷新内存队列，又将其中的数据写入到对端。
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) { //yangyc write + flush 的组合，将数据写到内存队列后，立即刷新内存队列，又将其中的数据写入到对端。
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     */
    protected abstract class AbstractUnsafe implements Unsafe {

        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        private RecvByteBufAllocator.Handle recvHandle;
        private boolean inFlush0;
        /** true if the channel has never been registered, false otherwise */
        private boolean neverRegistered = true;

        private void assertEventLoop() {
            assert !registered || eventLoop.inEventLoop();
        }

        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() { //yangyc 控制读循环 + 预测下次创建 ByteBuf 容量大小
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        @Override
        public final void register(EventLoop eventLoop, final ChannelPromise promise) { //yangyc-main 在 EventLoop 中执行注册逻辑
            ObjectUtil.checkNotNull(eventLoop, "eventLoop"); //yangyc 校验传入的 eventLoop 非空
            if (isRegistered()) { //yangyc 校验未注册，防止重复注册
                promise.setFailure(new IllegalStateException("registered to an event loop already")); //yangyc 1.设置promise结果失败，2.回调监听者，执行失败的逻辑
                return;
            }
            if (!isCompatible(eventLoop)) { //yangyc 校验 Channel 和 eventLoop 匹配
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }

            AbstractChannel.this.eventLoop = eventLoop; //yangyc 设置 Channel 的 eventLoop 属性，这个channel就是unsafe的外层对象。绑定关系，后续的channel上的事件或者任务都依赖当前EventLoop线程去处理。服务端：AbstractChannel.this=>NioServerSocketChannel对象

            if (eventLoop.inEventLoop()) { //yangyc 为了线程安全。判断当前线程是不是当前eventloop自己的线程
                register0(promise); //yangyc 如果是当前线程，在 EventLoop 执行注册逻辑（channel先unregister再register走这里，否则走下面那个）
            } else {
                try {
                    eventLoop.execute(new Runnable() { //yangyc-main 提交异步任务到eventloop工作队列--异步任务1 SingleThreadEventExecutor#execute()
                        @Override
                        public void run() { //yangyc-main 提交异步任务到eventloop工作队列:任务1 将注册任务提交到eventloop工作队列中 【异步任务1--添加】--- xx#xx()时执行---
                            register0(promise); //yangyc-main 如果不是当前线程， 在 EventLoop 中执行注册逻辑
                        }
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    closeFuture.setClosed();
                    safeSetFailure(promise, t);
                }
            }
        }

        private void register0(ChannelPromise promise) { //yangyc-main 异步任务1执行 由当前channel关联的EventLoop线程执行，参数：promise 表示注册结果，外部可以向它注册监听者..来完成注册后的逻辑
            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                if (!promise.setUncancellable() || !ensureOpen(promise)) { //yangyc 确保 Channel 是打开的
                    return;
                }
                boolean firstRegistration = neverRegistered; //yangyc 记录是否为首次注册
                doRegister(); //yangyc-main 执行注册逻辑 -- JDK 层面
                neverRegistered = false; //yangyc 标记首次注册为 false
                registered = true;  //yangyc 标记 Channel 为已注册到多路复用器

                // Ensure we call handlerAdded(...) before we actually notify the promise. This is needed as the
                // user may already fire events through the pipeline in the ChannelFutureListener.
                pipeline.invokeHandlerAddedIfNeeded(); //yangyc-main ChannelInitializer 的拆包，注册执行之后，通过一系列操作，调用到ChannelInitializer#initChannel进行拆包，并且移除自己。【异步任务2--执行】---在ServerBootstrap#init(...)添加---ch.eventLoop().execute...

                safeSetSuccess(promise); //yangyc 设置regFuture状态为成功，并回调注册相关的promise上注册的Listener事件
                pipeline.fireChannelRegistered(); //yangyc 向当前 Channel 的 pipeline 发起注册完成事件， 关注注册事件的 Handle 做一些事情
                // Only fire a channelActive if the channel has never been registered. This prevents firing
                // multiple channel actives if the channel is deregistered and re-registered.
                if (isActive()) { //yangyc isActive()=>是否完成绑定， NioServerSoceketChannel时，是未绑定的，不走下面逻辑. NioSocketChannel 会走下面逻辑
                    if (firstRegistration) {
                        pipeline.fireChannelActive();
                    } else if (config().isAutoRead()) {
                        // This channel was registered before and autoRead() is set. This means we need to begin read
                        // again so that we process inbound data.
                        //
                        // See https://github.com/netty/netty/issues/4805
                        beginRead();
                    }
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            assertEventLoop(); //yangyc 判断是否在 EventLoop 的线程中 ---- 只允许在 EventLoop 的线程中执行

            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                localAddress instanceof InetSocketAddress &&
                !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                        "is not bound to a wildcard address; binding to a non-wildcard " +
                        "address (" + localAddress + ") anyway as requested.");
            }

            boolean wasActive = isActive(); //yangyc 记录 Channel 是否激活 ---- 判断 ServerSocketChannel 是否绑定端口
            try {
                doBind(localAddress); //yangyc 绑定 Channel 的端口 ---- （Java 原生 NIO ServerSocketChannel ）
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (!wasActive && isActive()) { //yangyc 若 Channel 是新激活的，触发通知 Channel 已激活的事件
                invokeLater(new Runnable() {
                    @Override
                    public void run() { //yangyc headContext 会响应 active 事件，再次向当前 Channel 的 pipeline 发起 read 事件。read 事件，就会修改channel在selector上注册感兴趣的事件为accept
                        pipeline.fireChannelActive(); //yangyc-main 提交异步任务到eventloop工作队列: 【异步任务4--添加】--- xx#xx()时执行---
                    }
                });
            }

            safeSetSuccess(promise); //yangyc 回调通知 promise 执行成功，并且唤醒主线程，因为我们在启动线程的时候，在该promise上执行wait操作，所以绑定完成之后要将其唤醒
        }

        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                doDisconnect();
                // Reset remoteAddress and localAddress
                remoteAddress = null;
                localAddress = null;
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            safeSetSuccess(promise);
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public void close(final ChannelPromise promise) { //yangyc 关闭 Channel
            assertEventLoop();

            ClosedChannelException closedChannelException =
                    StacklessClosedChannelException.newInstance(AbstractChannel.class, "close(ChannelPromise)");
            close(promise, closedChannelException, closedChannelException, false);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         */
        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         * @param cause The cause which may provide rational for the shutdown.
         */
        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            if (!promise.setUncancellable()) {
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                promise.setFailure(new ClosedChannelException());
                return;
            }
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);

            // When a side enables SO_LINGER and calls showdownOutput(...) to start TCP half-closure
            // we can not call doDeregister here because we should ensure this side in fin_wait2 state
            // can still receive and process the data which is send by another side in the close_wait state。
            // See https://github.com/netty/netty/issues/11981
            try {
                // The shutdown function does not block regardless of the SO_LINGER setting on the socket
                // so we don't need to use GlobalEventExecutor to execute the shutdown
                doShutdownOutput();
                promise.setSuccess();
            } catch (Throwable err) {
                promise.setFailure(err);
            } finally {
                closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
            }
        }

        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            buffer.failFlushed(cause, false);
            buffer.close(cause, true);
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);
        }

        private void close(final ChannelPromise promise, final Throwable cause, //yangyc 关闭 Channel
                           final ClosedChannelException closeCause, final boolean notify) {
            if (!promise.setUncancellable()) {
                return; //yangyc 设置 Promise 不可取消
            }

            if (closeInitiated) { //yangyc 若关闭已经标记初始化
                if (closeFuture.isDone()) { //yangyc 关闭已经完成，直接通知 Promise 对象
                    // Closed already.
                    safeSetSuccess(promise);
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    closeFuture.addListener(new ChannelFutureListener() { //yangyc 关闭未完成，通过监听器通知 Promise 对象
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }

            closeInitiated = true; //yangyc 标记关闭已经初始化

            final boolean wasActive = isActive(); //yangyc 获得 Channel 是否激活
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;  //yangyc 标记 outboundBuffer 为空
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
            Executor closeExecutor = prepareToClose();  //yangyc 执行准备关闭
            if (closeExecutor != null) {  //yangyc 若 closeExecutor 非空
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the close.
                            doClose0(promise); //yangyc 在 closeExecutor 中，执行关闭
                        } finally {
                            // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    if (outboundBuffer != null) {
                                        // Fail all the queued messages
                                        outboundBuffer.failFlushed(cause, notify); //yangyc 写入数据( 消息 )到对端失败，通知相应数据对应的 Promise 失败
                                        outboundBuffer.close(closeCause);  //yangyc 关闭内存队列
                                    }
                                    fireChannelInactiveAndDeregister(wasActive); //yangyc 执行取消注册，并触发 Channel Inactive 事件到 pipeline 中
                                }
                            });
                        }
                    }
                });
            } else { //yangyc 若 closeExecutor 为空
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise); // 执行关闭
                } finally {
                    if (outboundBuffer != null) {
                        // Fail all the queued messages.
                        outboundBuffer.failFlushed(cause, notify); //yangyc 写入数据( 消息 )到对端失败，通知相应数据对应的 Promise 失败。
                        outboundBuffer.close(closeCause); //yangyc 关闭内存队列
                    }
                }
                if (inFlush0) { //yangyc 正在 flush 中，在 EventLoop 中执行执行取消注册，并触发 Channel Inactive 事件到 pipeline 中
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {  //yangyc 不在 flush 中，直接执行执行取消注册，并触发 Channel Inactive 事件到 pipeline 中
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        private void doClose0(ChannelPromise promise) {
            try {
                doClose();
                closeFuture.setClosed();
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            deregister(voidPromise(), wasActive && !isActive());
        }

        @Override
        public final void closeForcibly() {
            assertEventLoop();

            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();

            deregister(promise, false);
        }

        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is needed as for example,
            // we may be in the ByteToMessageDecoder.callDecode(...) method and so still try to do processing in
            // the old EventLoop while the user already registered the Channel to a new EventLoop. Without delay,
            // the deregister operation this could lead to have a handler invoked by different EventLoop and so
            // threads.
            //
            // See:
            // https://github.com/netty/netty/issues/4435
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        doDeregister();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                    } finally {
                        if (fireChannelInactive) {
                            pipeline.fireChannelInactive();
                        }
                        // Some transports like local and AIO does not allow the deregistration of
                        // an open channel.  Their doDeregister() calls close(). Consequently,
                        // close() calls deregister() again - no need to fire channelUnregistered, so check
                        // if it was registered.
                        if (registered) {
                            registered = false;
                            pipeline.fireChannelUnregistered();
                        }
                        safeSetSuccess(promise);
                    }
                }
            });
        }

        @Override
        public final void beginRead() { //yangyc 开始读取操作
            assertEventLoop(); //yangyc 判断是否在 EventLoop 的线程中。

            try {
                doBeginRead(); //yangyc 执行开始读取 ---- AbstractNioMessageChannel#doBeginRead
            } catch (final Exception e) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        @Override //yangyc 参数1：一般是ByteBuf对象，其他是FileRegion; 参数2：本地写操作释放成功或失败的promise，可以注册监听事件
        public final void write(Object msg, ChannelPromise promise) { //yangyc 将数据写到内存队列中
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer; //yangyc 每个channel都有一个属于它自己的 unsafe, 每个unsafe都有一个属于它自己的 outbountBuffer
            if (outboundBuffer == null) { //yangyc 内存队列为空,一般是 Channel 已经关闭，所以通知 Promise 异常结果
                try {
                    // release message now to prevent resource-leak
                    ReferenceCountUtil.release(msg); //yangyc 释放消息( 对象 )相关的资源
                } finally {
                    // If the outboundBuffer is null we know the channel was closed and so
                    // need to fail the future right away. If it is not null the handling of the rest
                    // will be done in flush0()
                    // See https://github.com/netty/netty/issues/2362
                    safeSetFailure(promise,
                            newClosedChannelException(initialCloseCause, "write(Object, ChannelPromise)")); //yangyc 通知 Promise 异常结果
                }
                return;
            }

            int size; //yangyc msg 数据真正大小
            try {
                msg = filterOutboundMessage(msg); //yangyc 过滤写入的消息(数据); msg一般是ByreBuf对象，分为 heap和direct; 如果是heap类型，会被转为direct类型 --- [AbstractNioByteChannel.java]
                size = pipeline.estimatorHandle().size(msg); //yangyc 计算当前消息有效数据量大小
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                try {
                    ReferenceCountUtil.release(msg); //yangyc 释放消息( 对象 )相关的资源
                } finally {
                    safeSetFailure(promise, t);  //yangyc 通知 Promise 异常结果
                }
                return;
            }

            outboundBuffer.addMessage(msg, size, promise); //yangyc 将ByteBuf数据加入出栈缓冲区。参数1：ByteBuf对象，并且归属于direct; 参数2：数据量大小； 参数3：本地写操作释放成功或失败的promise，可以注册监听事件
        }

        @Override
        public final void flush() { //yangyc 刷新内存队列，将其中的数据写入到对端
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                return; //yangyc 内存队列为 null ，一般是 Channel 已经关闭，所以直接返回。
            }

            outboundBuffer.addFlush(); //yangyc 准备刷新工作：1. 将 flushedEntry 指向第一个需要刷新的 entry 节点； 2. 计算出 flushedEntry->tailEntry 总共有多少个 entry 需要被刷新； 值记录在flushed字段
            flush0(); //yangyc 执行 flush
        }

        @SuppressWarnings("deprecation")
        protected void flush0() {
            if (inFlush0) {
                // Avoid re-entrance
                return; //yangyc 正在 flush 中，所以直接返回
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return; //yangyc 内存队列为 null ，一般是 Channel 已经关闭，所以直接返回
            }

            inFlush0 = true;  //yangyc 标记当前 channel 正在执行刷新工作

            // Mark all pending write requests as failure if the channel is inactive.
            if (!isActive()) { //yangyc 若未激活，通知 flush 失败
                try {
                    // Check if we need to generate the exception at all.
                    if (!outboundBuffer.isEmpty()) {
                        if (isOpen()) {
                            outboundBuffer.failFlushed(new NotYetConnectedException(), true);
                        } else {
                            // Do not trigger channelWritabilityChanged because the channel is closed already.
                            outboundBuffer.failFlushed(newClosedChannelException(initialCloseCause, "flush0()"), false);
                        }
                    }
                } finally {
                    inFlush0 = false; //yangyc 标记不在 flush 中
                }
                return;
            }

            try {
                doWrite(outboundBuffer); //yangyc 执行真正的写入到对端
            } catch (Throwable t) {
                handleWriteError(t);
            } finally {
                inFlush0 = false; //yangyc 标记不在 flush 中
            }
        }

        protected final void handleWriteError(Throwable t) {
            if (t instanceof IOException && config().isAutoClose()) {
                /**
                 * Just call {@link #close(ChannelPromise, Throwable, boolean)} here which will take care of
                 * failing all flushed messages and also ensure the actual close of the underlying transport
                 * will happen before the promises are notified.
                 *
                 * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #isWritable()}
                 * may still return {@code true} even if the channel should be closed as result of the exception.
                 */
                initialCloseCause = t;
                close(voidPromise(), t, newClosedChannelException(t, "flush0()"), false);
            } else {
                try {
                    shutdownOutput(voidPromise(), t);
                } catch (Throwable t2) {
                    initialCloseCause = t;
                    close(voidPromise(), t2, newClosedChannelException(t, "flush0()"), false);
                }
            }
        }

        private ClosedChannelException newClosedChannelException(Throwable cause, String method) {
            ClosedChannelException exception =
                    StacklessClosedChannelException.newInstance(AbstractChannel.AbstractUnsafe.class, method);
            if (cause != null) {
                exception.initCause(cause);
            }
            return exception;
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();

            return unsafeVoidPromise;
        }

        protected final boolean ensureOpen(ChannelPromise promise) { //yangyc 确保 Channel 是打开的
            if (isOpen()) {
                return true;
            }

            safeSetFailure(promise, newClosedChannelException(initialCloseCause, "ensureOpen(ChannelPromise)")); //yangyc 若未打开，回调通知 promise 异常
            return false;
        }

        /**
         * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetSuccess(ChannelPromise promise) {  //yangyc 关闭已经完成，直接通知 Promise 对象
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        private void invokeLater(Runnable task) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //      -> channel.unsafe.close()
                //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task); //yangyc 提交一个新的任务到 EventLoop 的线程中
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }

        /**
         * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
         * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
         * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
         * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
         */
        protected Executor prepareToClose() {
            return null;
        }
    }

    /**
     * Return {@code true} if the given {@link EventLoop} is compatible with this instance.
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register process.
     *
     * Sub-classes may override this method
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Called when conditions justify shutting down the output portion of the channel. This may happen if a write
     * operation throws an exception.
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     *
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception { //yangyc 过滤写入的消息( 数据 )
        return msg;
    }

    protected void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }
}
