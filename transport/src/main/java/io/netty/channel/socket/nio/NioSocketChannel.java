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
 * yangyc NioSocketChannel 读取( read )对端的数据的过程，简单来说：
 * 1. NioSocketChannel 所在的 EventLoop 线程轮询是否有新的数据写入。
 * 2. 当轮询到有新的数据写入，NioSocketChannel 读取数据，并提交到 pipeline 中进行处
 */
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel { //yangyc NioSocketChannel 读取新的数据
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();  //yangyc 静态属性，默认的 SelectorProvider 实现类。

    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final SocketChannelConfig config; //yangyc Channel 对应的配置对象

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, SocketChannel socket) { //yangyc 参数1：NioServerSocketChannel, 参数2：JDK 层面的 SocketChannel
        super(parent, socket); //yangyc-main 调用父类 AbstractNioByteChannel 的构造方法, 感兴趣事件--SelectionKey.OP_READ
        config = new NioSocketChannelConfig(this, socket.socket()); //yangyc-main 初始化 config 属性，创建 NioSocketChannelConfig 对象
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override
    public boolean isActive() { //yangyc 获得 Channel 是否激活
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected(); //yangyc 是否处于打开，并且连接的状态
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override
    public boolean isInputShutdown() {
        return javaChannel().socket().isInputShutdown() || !isActive();
    }

    @Override
    public boolean isShutdown() {
        Socket socket = javaChannel().socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdownInput() {
        return shutdownInput(newPromise());
    }

    @Override
    protected boolean isInputShutdown0() {
        return isInputShutdown();
    }

    @Override
    public ChannelFuture shutdownInput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            shutdownInput0(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownInput0(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(final ChannelPromise promise) {
        ChannelFuture shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(shutdownOutputFuture, promise);
        } else {
            shutdownOutputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
                    shutdownOutputDone(shutdownOutputFuture, promise);
                }
            });
        }
        return promise;
    }

    private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
        ChannelFuture shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
                }
            });
        }
    }

    private static void shutdownDone(ChannelFuture shutdownOutputFuture,
                                     ChannelFuture shutdownInputFuture,
                                     ChannelPromise promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess();
        }
    }
    private void shutdownInput0(final ChannelPromise promise) {
        try {
            shutdownInput0();
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    private void shutdownInput0() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownInput();
        } else {
            javaChannel().socket().shutdownInput();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            doBind0(localAddress);  //yangyc 绑定本地地址 --- 默认情况下，系统会随机分配一个可用的本地地址，进行绑定
        }

        boolean success = false; //yangyc 执行是否成功
        try {
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);  //yangyc 连接远程地址
            if (!connected) {
                selectionKey().interestOps(SelectionKey.OP_CONNECT); //yangyc 若未连接完成，则关注连接( OP_CONNECT )事件。
            }
            success = true; //yangyc 标记执行是否成功
            return connected;  //yangyc 返回是否连接完成
        } finally {
            if (!success) {
                doClose(); //yangyc 执行失败，则关闭 Channel
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception { //yangyc 执行完成连接的逻辑
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        javaChannel().close();
    }

    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle(); //yangyc 获得 RecvByteBufAllocator.Handle 对象
        allocHandle.attemptedBytesRead(byteBuf.writableBytes()); //yangyc 设置最大可读取字节数量。因为 ByteBuf 目前最大写入的大小为 byteBuf.writableBytes()
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead()); //yangyc 读取数据到 ByteBuf 中 --- 默认PooledUnsafeDirectByteBuf。 参数1：JDK层面的SocketChannel实例，参数2：length,想要读取的数据量; 返回真实从 SocketChannel 读取的数据量
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transferred();
        return region.transferTo(javaChannel(), position);
    }

    private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
        // By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
        // SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
        // make a best effort to adjust as OS behavior changes.
        if (attempted == written) {
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception { //yangyc 执行真正的写入到对端； 当前 channel 出栈缓冲区
        SocketChannel ch = javaChannel(); //yangyc 获得 Java NIO 原生 SocketChannel
        int writeSpinCount = config().getWriteSpinCount(); //yangyc 获得自旋写入次数, 默认16次。 表示 do...while 最多执行16次
        do {
            if (in.isEmpty()) { //yangyc 条件成立，说明出栈缓冲区内待刷新的entry都预计处理完毕，结束循环，直接返回
                // All written so clear OP_WRITE yangyc 因为在 Channel 不可写的时候，会注册 SelectionKey.OP_WRITE ，等待 NIO Channel 可写。而后会”回调” #forceFlush() 方法，该方法内部也会调用 #doWrite(ChannelOutboundBuffer in) 方法。所以在完成内部队列的数据向对端写入时候，需要调用 #clearOpWrite() 方法
                clearOpWrite(); //yangyc 正常退出之前，将当前 ch 在 selector 上注册的 OP_WRITE 清理掉，不然又会触发到write事件
                // Directly return here so incompleteWrite(...) is not called.
                return; //yangyc 正常退出 do...while 都是从这里结束
            }
            //yangyc 执行到这里，说明当前 ch 出栈缓冲区内有剩余 entry 待刷新
            // Ensure the pending writes are made of ByteBufs only.
            int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite(); //yangyc 限定每次从出栈缓冲区内转换多少 byteBuf 字节数据的一个变量；该变量会随着ch的状态不断变化
            ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite); //yangyc 将出栈缓冲区内的部分 entry.msg 转换成 JDK channel 依赖的标准对象 ByteBuffer； 返回 ByteBuffer 数组。 参数1：最多转换出1024个ByteBuffer对象，参数2:最多转换 maxBytes 字节的ByteBud
            int nioBufferCnt = in.nioBufferCount();  //yangyc 获取出上一步转换的 byteBuffer 的数量

            // Always use nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) { //yangyc 写入 ByteBuffer 数组，到对端
                case 0:
                    // We have something else beside ByteBuffers to write so fallback to normal writes.
                    writeSpinCount -= doWrite0(in);
                    break;
                case 1: {
                    // Only one ByteBuf so use non-gathering write
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    ByteBuffer buffer = nioBuffers[0]; //yangyc 因为 nioBufferCnt 是1， 所以刚刚 nioBuffers 方法只转换出一个 buteBuffer 对象
                    int attemptedBytes = buffer.remaining(); //yangyc 获取 buteBuffer 有效数据大小
                    final int localWrittenBytes = ch.write(buffer); //yangyc 使用 JDK 层面的 ch.write 将 buffer 内的数据写入到 socket 写缓冲区。返回值：本次write真正写入socket的大小
                    if (localWrittenBytes <= 0) { //yangyc 条件成立：说明底层 socket 写缓冲区已经满了，本次 write 没写进去
                        incompleteWrite(true);  //yangyc 设置ch在多路复用器上注册的事件，关注OP_WRITE, 当底层 socket 写缓冲区有空闲空间后，多路复用器会再次唤醒当前 NioEnventLoop 线程，再去处理当前 ch 剩余的待写数据
                        return;
                    }
                    //yangyc 执行到这里，说明 buffer 可能全部写入到 socket 缓冲区 或者 一部分写入到socket缓冲区
                    adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite); //yangyc 调整每次写入的最大字节数
                    in.removeBytes(localWrittenBytes);  //yangyc 将真正写入到 socket 写缓冲区的字节从出栈缓冲区移除； 参数：真正写入到 socket 写缓冲区的大小
                    --writeSpinCount;
                    break; //yangyc 写入次数减一
                }
                default: {
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    // We limit the max amount to int above so cast is safe
                    long attemptedBytes = in.nioBufferSize();
                    final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt); //yangyc 执行 NIO write 调用，写入多个 ByteBuffer 到对端
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true); //yangyc 写入字节小于等于 0 ，说明 NIO Channel 不可写，所以注册 SelectionKey.OP_WRITE ，等待 NIO Channel 可写，并返回以结束循环
                        return;
                    }
                    // Casting to int is safe because we limit the total amount of data in the nioBuffers to int above.
                    adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
                            maxBytesPerGatheringWrite); //yangyc 调整每次写入的最大字节数
                    in.removeBytes(localWrittenBytes);  //yangyc 从内存队列中，移除已经写入的数据( 消息 )
                    --writeSpinCount; //yangyc 写入次数减一
                    break;
                }
            }
        } while (writeSpinCount > 0);
        //yangyc 执行到这里，说明do...while 循环16次，仍然没有能将出栈缓冲区待发送的数据处理完。 incompleteWrite() 提交一个 flushTask,最终会调用 dowrite(),注意调用dowrite之前并没有addFlush...
        //yangyc 为什么是提交 flushTask, 而不是继续干？ 避免多路复用器其他ch饥饿； 让其他NioEventLoop线程接下来处理其他ch上的事件，回过头处理完IO之后，再让NioEventLoop本地任务队列内的任务，处理任务就会碰到 flushTask, 就有机会继续完成当前ch剩余的待发送数据了。。。
        incompleteWrite(writeSpinCount < 0);   //yangyc 内存队列中的数据未完全写入，说明 NIO Channel 不可写，所以注册 SelectionKey.OP_WRITE ，等待 NIO Channel 可写
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }

    private final class NioSocketChannelUnsafe extends NioByteUnsafe {
        @Override
        protected Executor prepareToClose() {
            try {
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // We need to cancel this key of the channel so we may not end up in a eventloop spin
                    // because we try to read or write until the actual close happens which may be later due
                    // SO_LINGER handling.
                    // See https://github.com/netty/netty/issues/4449
                    doDeregister();
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
                // Ignore the error as the underlying channel may be closed in the meantime and so
                // getSoLinger() may produce an exception. In this case we just return null.
                // See https://github.com/netty/netty/issues/4449
            }
            return null;
        }
    }

    private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
        private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket);
            calculateMaxBytesPerGatheringWrite();
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
            super.setSendBufferSize(sendBufferSize);
            calculateMaxBytesPerGatheringWrite();
            return this;
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
            this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
        }

        int getMaxBytesPerGatheringWrite() {
            return maxBytesPerGatheringWrite;
        }

        private void calculateMaxBytesPerGatheringWrite() {
            // Multiply by 2 to give some extra space in case the OS can process write data faster than we can provide.
            int newSendBufferSize = getSendBufferSize() << 1;
            if (newSendBufferSize > 0) {
                setMaxBytesPerGatheringWrite(newSendBufferSize);
            }
        }

        private SocketChannel jdkChannel() {
            return ((NioSocketChannel) channel).javaChannel();
        }
    }
}
