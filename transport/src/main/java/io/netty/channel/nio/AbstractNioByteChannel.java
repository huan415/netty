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
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) { //yangyc 参数1：NioServerSocketChannel, 参数2：JDK 层面的 SocketChannel,参数3：感兴趣的事件
        super(parent, ch, SelectionKey.OP_READ); //yangyc-main 设置感兴趣事件 SelectionKey.OP_READ
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) { //yangyc byteBuf 非空，说明在发生异常之前，至少申请 ByteBuf 对象是成功的
                if (byteBuf.isReadable()) { //yangyc ByteBuf 对象是否可读，即剩余可读的字节数据
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf); //yangyc 触发 Channel read 事件到 pipeline 中。
                } else {
                    byteBuf.release(); //yangyc 释放 ByteBuf 对象
                }
            }
            allocHandle.readComplete(); //yangyc 读取完成
            pipeline.fireChannelReadComplete(); //yangyc 触发 Channel readComplete 事件到 pipeline 中
            pipeline.fireExceptionCaught(cause); //yangyc 触发 exceptionCaught 事件到 pipeline 中。

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            final ChannelConfig config = config();  //yangyc 获取客户端 Channel#config 对象
            if (shouldBreakReadReady(config)) {
                clearReadPending(); //yangyc 若 inputClosedSeenErrorOnRead = true ，移除对 SelectionKey.OP_READ 事件的感兴趣
                return;
            }
            final ChannelPipeline pipeline = pipeline(); //yangyc 获取客户端 Channel#pipeline 对象
            final ByteBufAllocator allocator = config.getAllocator();  //yangyc 获取缓冲区分配器 allocator。如果平台不是安卓的，缓冲区分配器就是池化管理的 PooledByteBufAllocator
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();  //yangyc 控制读循环 + 预测下次创建 ByteBuf 容量大小
            allocHandle.reset(config);  //yangyc 重置 RecvByteBufAllocator.Handle 对象

            ByteBuf byteBuf = null; //yangyc 对 JDK 层面的 ByteBuffer的增强接口实现，缓冲区里面包装者内存，提供去读取 socket 读缓冲区内的业务数据
            boolean close = false; //yangyc 是否关闭连接
            try {
                do { //yangyc 循环 读取新的写入数据
                    byteBuf = allocHandle.allocate(allocator); //yangyc 申请 ByteBuf 对象；参数：池化内存管理的缓冲区分配器。 allocHandle作用：预测分配多大内存
                    allocHandle.lastBytesRead(doReadBytes(byteBuf)); //yangyc 更新缓冲区预测分配器最后一次读取数据量. doReadBytes(byteBuf)=>返回真实从 SocketChannel 内读取的数据量
                    if (allocHandle.lastBytesRead() <= 0) { //yangyc 未读取到数据。情况：1.channel底层socket缓冲区已经读取完毕返回0； 2:channel对端关闭了返回-1 --- [RecvByteBufAllocator.Handle#lastBytesRead]
                        // nothing was read. release the buffer.
                        byteBuf.release(); //yangyc 释放 ByteBuf 对象
                        byteBuf = null; //yangyc 置空 ByteBuf 对象
                        close = allocHandle.lastBytesRead() < 0; //yangyc 如果最后读取的字节为小于 0 ，说明对端已经关闭
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break; //yangyc 结束循环
                    }

                    allocHandle.incMessagesRead(1); //yangyc 更新缓冲区预测分配器读取的消息数量； 读取消息数量 + localRead
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);  //yangyc 向客户端pipeline发起channelRead事件, pipeline中 实现了channelRead的handler就可以进行业务处理
                    byteBuf = null;
                } while (allocHandle.continueReading());

                allocHandle.readComplete(); //yangyc 读取完成
                pipeline.fireChannelReadComplete(); //yangyc 触发 Channel readComplete 事件到 pipeline 中。 设置客户端 Selector 包含read标记，表示Selector需要继续帮当前Channel监听read事件

                if (close) {
                    closeOnRead(pipeline); //yangyc 关闭客户端的连接
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) { //yangyc 过滤写入的消息( 数据 )
        if (msg instanceof ByteBuf) { //yangyc ByteBuf 类型, 需要封装成 newDirectBuffer, 目的：Socket 传递数据时newDirectBuffer性能很好
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) { //yangyc FileRegion 类型,
            return msg;
        }

        throw new UnsupportedOperationException( //yangyc 不支持其他类型
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            setOpWrite(); //true：yangyc 注册对 SelectionKey.OP_WRITE 事件感兴趣
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            clearOpWrite(); //false: yangyc 取消对 SelectionKey.OP_WRITE 事件感兴趣

            // Schedule flush again later so other tasks can be picked up in the meantime
            eventLoop().execute(flushTask); //yangyc 立即发起下一次 flush 任务
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception; //yangyc 读取写入的数据,返回读取到的字节数，返回值小于 0 时，表示对端已经关闭

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() { //yangyc 注册对 SelectionKey.OP_WRITE 事件感兴趣
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;  //yangyc 不合法直接返回
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);  //yangyc 注册 SelectionKey.OP_WRITE 事件的感兴趣
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return; //yangyc 不合法，直接返回
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE); //yangyc 若注册了 SelectionKey.OP_WRITE ，则进行取消
        }
    }
}
