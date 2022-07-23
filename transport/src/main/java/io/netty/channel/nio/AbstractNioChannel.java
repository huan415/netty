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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    private final SelectableChannel ch;
    protected final int readInterestOp;
    volatile SelectionKey selectionKey;
    boolean readPending;
    private final Runnable clearReadPendingRunnable = new Runnable() {
        @Override
        public void run() {
            clearReadPending0();
        }
    };

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    private ChannelPromise connectPromise;
    private Future<?> connectTimeoutFuture;
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     * //yangyc 服务端：参数1：null, 参数2:JDK层面的ServerSocketChannel, 参数3:感兴趣的时间，连接事件
     *  //yangyc 客户端 参数1：NioServerSocketChannel, 参数2：JDK 层面的 SocketChannel,参数3：感兴趣的事件
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) { //yangyc-main 初始化ServerChannel的pipeline + 设置成非阻塞
        super(parent); //yangyc-main 调用父 AbstractNioChannel 的构造方法, 在父类里 newChannelPipeline() 初始化pipeline
        this.ch = ch; //yangyc Netty NIO Channel 对象，持有的 Java 原生 NIO 的 Channel 对象
        this.readInterestOp = readInterestOp; //yangyc 感兴趣的读事件的操作位值, AbstractNioMessageChannel是SelectionKey.OP_ACCEPT； AbstractNioByteChannel（NioServerSocketChannel）是SelectionKey.OP_READ
        try {
            ch.configureBlocking(false); //yangyc-main 设置成非阻塞
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException e2) {
                logger.warn(
                            "Failed to close a partially initialized socket.", e2);
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        setReadPending0(readPending);
                    }
                });
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * Set read pending to {@code false}.
     */
    protected final void clearReadPending() {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                clearReadPending0(); //yangyc 移除对“读”事件的感兴趣
            } else {
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    private void clearReadPending0() {
        readPending = false;
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            if (!key.isValid()) {
                return; //yangyc 忽略，如果 SelectionKey 不合法，例如已经取消
            }
            int interestOps = key.interestOps(); //yangyc 移除对“读”事件的感兴趣
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }

        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                if (connectPromise != null) { //yangyc 目前有正在连接远程地址的 ChannelPromise ，则直接抛出异常，禁止同时发起多个连接
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                boolean wasActive = isActive(); //yangyc 记录 Channel 是否激活
                if (doConnect(remoteAddress, localAddress)) { //yangyc 执行连接远程地址
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    connectPromise = promise; //yangyc 记录 connectPromise
                    requestedRemoteAddress = remoteAddress; //yangyc 记录 requestedRemoteAddress

                    // Schedule connect timeout.
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    if (connectTimeoutMillis > 0) { //yangyc 使用 EventLoop 发起定时任务，监听连接远程地址超时。若连接超时，则回调通知 connectPromise 超时异常
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() { //yangyc 监听连接远程地址是否超时。若连接超时，则回调通知 connectPromise 超时异常
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                if (connectPromise != null && !connectPromise.isDone()
                                        && connectPromise.tryFailure(new ConnectTimeoutException(
                                                "connection timed out: " + remoteAddress))) {
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception { //yangyc 添加监听器，监听连接远程地址取消。
                            if (future.isCancelled()) { //yangyc 若取消，则取消 connectTimeoutFuture 任务，并置空 connectPromise
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false); //yangyc 取消定时任务
                                }
                                connectPromise = null;  //yangyc 置空 connectPromise
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                promise.tryFailure(annotateConnectException(t, remoteAddress)); //yangyc 回调通知 promise 发生异常
                closeIfClosed();
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive(); //yangyc 获得 Channel 是否激活

            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess(); //yangyc 回调通知 promise 执行成功

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) { //yangyc Channel 新激活的
                pipeline().fireChannelActive(); //yangyc 若 Channel 是新激活的，触发通知 Channel 已激活的事件。
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            closeIfClosed();
        }

        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            assert eventLoop().inEventLoop(); //yangyc 判断是否在 EventLoop 的线程中。

            try {
                boolean wasActive = isActive(); //yangyc 获得 Channel 是否激活
                doFinishConnect(); //yangyc 执行完成连接
                fulfillConnectPromise(connectPromise, wasActive); //yangyc 通知 connectPromise 连接完成
            } catch (Throwable t) {
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));  //yangyc 通知 connectPromise 连接异常
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                if (connectTimeoutFuture != null) {
                    connectTimeoutFuture.cancel(false); //yangyc 取消 connectTimeoutFuture 任务
                }
                connectPromise = null;   //yangyc 置空 connectPromise
            }
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            if (!isFlushPending()) {
                super.flush0();
            }
        }

        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    @Override
    protected void doRegister() throws Exception { //yangyc-main 执行注册逻辑 注册 Java 原生 NIO 的 Channel 对象到 Selector 对象上。
        boolean selected = false;
        for (;;) {
            try { //yangyc javaChannel() 返回 JDK 层面的 channel, 有可能是 ServerSocketChannel/SocketChannel
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this); //yangyc-main 参数1：JDK层面的多路复用器，参数2：ops 当前感兴趣的事件，参数3：att附件，可以拿到netty层面的对象，NioServerSocketChannel/ServerSocketChannel
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    eventLoop().selectNow();
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    @Override
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        final SelectionKey selectionKey = this.selectionKey;
        if (!selectionKey.isValid()) {
            return;
        }

        readPending = true;

        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
            selectionKey.interestOps(interestOps | readInterestOp); //yangyc 添加为感兴趣的事件 ---- 连接事件 readInterestOp = SelectionKey.OP_ACCEPT
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes(); //yangyc 获取当前ByteBuf可读数据量
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;
        }
        //yangyc 执行到这里，说明ByteBuf内有有效数据
        final ByteBufAllocator alloc = alloc(); //yanyc alloc 内存分配器 PooledByteBufAllocator
        if (alloc.isDirectBufferPooled()) { //yangyc 正常情况下条件成立
            ByteBuf directBuf = alloc.directBuffer(readableBytes); //yangyc 根据可读数据量，使用内存分配器分配一块指定大小的堆外内存ByteBuf对象
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes); //yangyc 将堆内ByteBuf数据拷贝到堆外ByteBuf对象
            ReferenceCountUtil.safeRelease(buf); //yangyc 释放堆内ByteBuf占用的内存
            return directBuf; //yangyc 返回堆外ByteBuf
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    @Override
    protected void doClose() throws Exception {
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(new ClosedChannelException());
            connectPromise = null;
        }

        Future<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
