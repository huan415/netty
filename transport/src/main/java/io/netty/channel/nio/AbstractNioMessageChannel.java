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
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) { //yangyc 参数1：null, 参数2:JDK层面的ServerSocketChannel, 参数3:感兴趣的时间，连接事件
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    protected boolean continueReading(RecvByteBufAllocator.Handle allocHandle) {
        return allocHandle.continueReading();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>(); //yangyc 新读取的客户端连接数组

        @Override
        public void read() { //yangyc-main “读取”新的客户端连接连入
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config(); //yangyc 服务端 Config 对象
            final ChannelPipeline pipeline = pipeline(); //yangyc 服务端 pipeline
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle(); //yangyc 控制读循环 + 预测下次创建 ByteBuf 容量大小
            allocHandle.reset(config); //yangyc 重置 RecvByteBufAllocator.Handle 对象

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do { //yangyc  循环 “读取”新的客户端连接连入; localRead 正常等于0
                        int localRead = doReadMessages(readBuf); //yangyc-main 读取消息到 readBuf 中，真正实现类在 NioServerSocketChannel
                        if (localRead == 0) {
                            break;  //yangyc 无可读取的客户端的连接，结束
                        }
                        if (localRead < 0) {  //yangyc 读取出错，说明当前服务器关闭
                            closed = true; //yangyc 标记关闭
                            break;
                        }

                        allocHandle.incMessagesRead(localRead); //yangyc 更新已读消息数量； 读取消息数量 + localRead --- 调用 AdaptiveRecvByteBufAllocator.HandleImpl#incMessagesRead(int amt)
                    } while (continueReading(allocHandle)); //yangyc 循环判断是否继续读取
                } catch (Throwable t) {
                    exception = t; //yangyc 记录异常
                }
                //yangyc 到这一步 readBuf 都是刚创建出来的客户端 Channel 对象
                int size = readBuf.size();  //yangyc 循环 readBuf 数组，触发 Channel read 事件到 pipeline 中。
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    pipeline.fireChannelRead(readBuf.get(i)); //yangyc-main 向服务端通道传播每个客户端Channel对象，通过 ServerBootstrapAcceptor ，将客户端的 Netty NioSocketChannel 注册到 EventLoop 上
                }
                readBuf.clear(); //yangyc 清空 readBuf 数组
                allocHandle.readComplete();  //yangyc 读取完成
                pipeline.fireChannelReadComplete();  //yangyc 触发 Channel readComplete 事件到 pipeline 中。 重新设置 selector 上当前 Server key, 让key包含accept, 就是让 selector 继续帮Server监听accept类型事件

                if (exception != null) {   //yangyc 发生异常
                    closed = closeOnReadError(exception); //yangyc 判断是否要关闭

                    pipeline.fireExceptionCaught(exception);  //yangyc 触发 exceptionCaught 事件到 pipeline 中。
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
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

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        int maxMessagesPerWrite = maxMessagesPerWrite();
        while (maxMessagesPerWrite > 0) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    maxMessagesPerWrite--;
                    in.remove();
                } else {
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    maxMessagesPerWrite--;
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
        if (in.isEmpty()) {
            // Wrote all messages.
            if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
            }
        } else {
            // Did not write all messages.
            if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                key.interestOps(interestOps | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception; //yangyc 读取客户端的连接到方法参数 buf 中

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
