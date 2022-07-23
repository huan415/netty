/*
 * Copyright 2013 The Netty Project
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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.lang.Math.min;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 * <p>
 * All methods must be called by a transport implementation from an I/O thread, except the following ones:
 * <ul>
 * <li>{@link #size()} and {@link #isEmpty()}</li>
 * <li>{@link #isWritable()}</li>
 * <li>{@link #getUserDefinedWritability(int)} and {@link #setUserDefinedWritability(int, boolean)}</li>
 * </ul>
 * yangyc 假设业务层面不断的调用ctx.write(msg)，msg最终调用unsafe.write(msg...) => channelOutboundBuffer.addMessage(msg)
 * e1 -> e2 -> e3 -> e4 -> e5 -> ...... -> eN
 * flushedEntry->null; unflushedEntry->e1;  tailEntry->eN
 * 业务hanlde接下来调用ctx.flush(); 最终调用unsafe.flush()
 * unsafe.flush(){
 *     1. channelOutboundBuffer.addFlush() 将flushedEntry指向unflushedEntry的元素，flushedEntry->e1
 *     2. channelOutboundBuffer.nioBuffers(...) 返回byteBuffer[]数组
 *     3. 遍历byteBuffer数组，调用JDK channel.write(buffer),返回真正写入socket缓冲区的字节数（res）
 *     4. 根据res移除出栈缓冲区内对应的entry
 * }
 * 下面情况3出现的逻辑：socket写缓冲区有可能被写满，假设写道byteBuffer[3]的时候，socket写缓冲区写满了，那么此时nioEventLoop在重试去写也没有用，怎么办？
 * 设置多路复用器当前ch关注OP_WRITE事件，当底层socket写缓冲区有空闲空间时，多路复用器会再次唤醒NioEventLoop线程去处理
 * 此时，flushedEntry->e4
 * 业务handler再次使用ctx.write(msg),那么unflushedEntry就指向了当前msg对应的entry
 * e4 -> e5 -> ...... -> eN -> eN+1
 * flushedEntry->e4;  unflushedEntry->eN+1;    tailEntry->eN+1;
 * </p>
 */
public final class ChannelOutboundBuffer { //yangyc 内存队列
    // Assuming a 64-bit JVM:
    //  - 16 bytes object header
    //  - 6 reference fields
    //  - 2 long fields
    //  - 2 int fields
    //  - 1 boolean field
    //  - padding
    static final int CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD =  //yangyc Entry 对象自身占用内存的大小
            SystemPropertyUtil.getInt("io.netty.transport.outboundBufferEntrySizeOverhead", 96);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final FastThreadLocal<ByteBuffer[]> NIO_BUFFERS = new FastThreadLocal<ByteBuffer[]>() {   //yangyc 线程对应的 ByteBuffer 数组缓存
        @Override
        protected ByteBuffer[] initialValue() throws Exception {
            return new ByteBuffer[1024];
        }
    };

    private final Channel channel; //yangyc 当前缓冲区归属 channel

    // Entry(flushedEntry) --> ... Entry(unflushedEntry) --> ... Entry(tailEntry)
    //
    // The Entry that is the first in the linked-list structure that was flushed
    private Entry flushedEntry; //yangyc 第一个( 开始 ) flush Entry(待刷新的第一个节点)  情况1.unflushedEntry!=null&&flushedEntry==null：此时出栈缓冲区处于入栈状态
    // The Entry which is the first unflushed in the linked-list structure
    private Entry unflushedEntry; //yangyc 第一个未 flush Entry(未刷新的第一个节点)      情况2.unflushedEntry==null&&flushedEntry！=null：此时出栈缓冲区处于出栈状态；调用addFlush之后，会将flushedEntry 指向 unflushedEntry的值，并计算出待刷新的节点数量 flushed 值
    // The Entry which represents the tail of the buffer
    private Entry tailEntry; //yangyc 尾 Entry（尾节点）                              情况3.unflushedEntry!=null&&flushedEntry！=null：情况比较少。
    // The number of flushed entries that are not written yet
    private int flushed; //yangyc 已 flush 但未写入对端的 Entry 数量（剩余多少entry带刷新到ch,addFlush方法中计算该值，计算方式：flushedEntry遍历到tailEntry，计算多少个元素）

    private int nioBufferCount; //yangyc 数组大小
    private long nioBufferSize; //yangyc 字节数

    private boolean inFail; //yangyc 正在通知 flush 失败中

    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER = //yangyc CAS totalPendingSize 的原子更新器
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    @SuppressWarnings("UnusedDeclaration")
    private volatile long totalPendingSize; //yangyc 出栈缓冲区总共有多少字节量，包括entry自身占用的空间，entry->msg + entry.filed

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> UNWRITABLE_UPDATER = //yangyc unwritable 的原子更新器
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "unwritable");

    @SuppressWarnings("UnusedDeclaration")
    private volatile int unwritable; //yangyc 出栈缓冲区是否可写，0：可写； 1：不可写

    private volatile Runnable fireChannelWritabilityChangedTask; //yangyc  触发 Channel 可写的改变的任务

    ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    /**
     * Add given message to this {@link ChannelOutboundBuffer}. The given {@link ChannelPromise} will be notified once
     * the message was written. yangyc 参数1：ByteBuf对象，并且归属于direct; 参数2：数据量大小； 参数3：本地写操作释放成功或失败的promise，可以注册监听事件
     */
    public void addMessage(Object msg, int size, ChannelPromise promise) { //yangyc 写入消息( 数据 )到内存队列, promise 只有在真正完成写入到对端操作，才会进行通知
        Entry entry = Entry.newInstance(msg, size, total(msg), promise); //yangyc 从对象池中获取一个包装当前msg的entry对象 参数1：ByteBuf对象，并且归属于direct; 参数2：数据量大小；参数3：total(msg)==size; 参数4：本地写操作释放成功或失败的promise，可以注册监听事件
        //yangyc 将包装当前msg的entry兑现加入到 entry 链表， 表示数据入栈到出栈缓冲区
        if (tailEntry == null) { //yangyc 若 tailEntry 为空，将 flushedEntry 也设置为空。防御型编程，实际不会出现
            flushedEntry = null;
        } else { //yangyc 若 tailEntry 非空，将原 tailEntry 指向新 Entry
            Entry tail = tailEntry;
            tail.next = entry;
        }
        tailEntry = entry;  //yangyc 更新 tailEntry 为新 Entry
        if (unflushedEntry == null) {
            unflushedEntry = entry;   //yangyc 若 unflushedEntry 为空，更新为新 Entry
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(entry.pendingSize, false);  //yangyc 累加出栈缓冲区总大小 totalPendingSize； 参数1：当前entry的 pendingSize; 参数2：false
    }

    /**
     * Add a flush to this {@link ChannelOutboundBuffer}. This means all previous added messages are marked as flushed
     * and so you will be able to handle them.
     */
    public void addFlush() { //yangyc 标记内存队列每个 Entry 对象，开始 flush
        // There is no need to process all entries if there was already a flush before and no new messages
        // where added in the meantime.
        //
        // See https://github.com/netty/netty/issues/2577
        Entry entry = unflushedEntry;
        if (entry != null) {
            if (flushedEntry == null) {
                // there is no flushedEntry yet, so start with the entry
                flushedEntry = entry; //yangyc 若 flushedEntry 为空，赋值为 unflushedEntry ，用于记录第一个( 开始 ) flush 的 Entry
            }
            do {
                flushed ++; //yangyc flushed 自增
                if (!entry.promise.setUncancellable()) { //yangyc 设置 Promise 不可取消
                    // Was cancelled so make sure we free up memory and notify about the freed bytes
                    int pending = entry.cancel();
                    decrementPendingOutboundBytes(pending, false, true);  //yangyc 设置失败，减少 totalPending 计数
                }
                entry = entry.next; //yangyc 下一个 Entry
            } while (entry != null);

            // All flushed so reset unflushedEntry
            unflushedEntry = null;  //yangyc 设置 unflushedEntry 为空，表示所有都 flush
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(long size) { //yangyc 增加 totalPendingSize 计数
        incrementPendingOutboundBytes(size, true);
    }

    private void incrementPendingOutboundBytes(long size, boolean invokeLater) { //yangyc 累加出栈缓冲区总大小 totalPendingSize； 参数1：当前entry的 pendingSize; 参数2：false
        if (size == 0) {
            return;
        }

        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size); //yangyc CAS 更新 totalPendingSize; 将size累计进去
        if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) { //yangyc 如果累加完之后，超过出栈缓冲区高水位
            setUnwritable(invokeLater); //yangyc 则设置 unwrieable 字段表示不可写； 并且向 pipeline 发起 unwrite 更改事件
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(long size) { //yangyc 减少 totalPendingSize 计数
        decrementPendingOutboundBytes(size, true, true);
    }

    private void decrementPendingOutboundBytes(long size, boolean invokeLater, boolean notifyWritability) { //yangyc 减少 totalPendingSize 计数
        if (size == 0) {
            return;
        }

        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);  //yangyc 减少 totalPendingSize 计数
        if (notifyWritability && newWriteBufferSize < channel.config().getWriteBufferLowWaterMark()) {
            setWritable(invokeLater); //yangyc totalPendingSize 小于低水位阀值时，设置为可写
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    /**
     * Return the current message to write or {@code null} if nothing was flushed before and so is ready to be written.
     */
    public Object current() { //yangyc 获得当前要写入对端的消息( 数据 )
        Entry entry = flushedEntry;
        if (entry == null) {
            return null;
        }

        return entry.msg;
    }

    /**
     * Return the current message flush progress.
     * @return {@code 0} if nothing was flushed before for the current message or there is no current message
     */
    public long currentProgress() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return 0;
        }
        return entry.progress;
    }

    /**
     * Notify the {@link ChannelPromise} of the current message about writing progress.
     */
    public void progress(long amount) { //yangyc 处理当前消息的 Entry 的写入进度，主要是通知 Promise 消息写入的进度
        Entry e = flushedEntry;
        assert e != null;
        ChannelPromise p = e.promise;
        long progress = e.progress + amount; //yangyc 设置 Entry 对象的 progress 属性
        e.progress = progress;
        if (p instanceof ChannelProgressivePromise) {
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total); //yangyc 通知 ChannelProgressivePromise 进度
        }
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as success and return {@code true}. If no
     * flushed message exists at the time this method is called it will return {@code false} to signal that no more
     * messages are ready to be handled.
     */
    public boolean remove() { //yangyc 移除当前消息对应的 Entry 对象，并 Promise 通知成功
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers(); //yangyc 清除 NIO ByteBuff 数组的缓存
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e); //yangyc 移除指定 Entry 对象。 一般是移动 flushedEntry 指向当前节点的下一个节点。并且更新 flued 字段

        if (!e.cancelled) {
            // only release message, notify and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);  //yangyc byteBuf 实现了引用计数，这里 safeRelease 更新引用计数； 最终可能触发 byteBuf 归还内存的逻辑
            safeSuccess(promise);  //yangyc 通知 Promise 执行成功
            decrementPendingOutboundBytes(size, false, true); //yangyc 原子减少出栈缓冲区总容量，减去移除的 entry.pendingSize
        }

        // recycle the entry
        e.recycle(); //yangyc 归还当前 entry 到对象缓冲池

        return true;
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as failure using the given {@link Throwable}
     * and return {@code true}. If no   flushed message exists at the time this method is called it will return
     * {@code false} to signal that no more messages are ready to be handled.
     */
    public boolean remove(Throwable cause) {
        return remove0(cause, true);
    }

    private boolean remove0(Throwable cause, boolean notifyWritability) { //yangyc 移除当前消息对应的 Entry 对象，并 Promise 通知异常
        Entry e = flushedEntry;
        if (e == null) {  //yangyc 所有 flush 的 Entry 节点，都已经写到对端
            clearNioBuffers(); //yangyc 清除 NIO ByteBuff 数组的缓存
            return false; //yangyc 没有后续的 flush 的 Entry 节点
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e);

        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg); //yangyc 释放消息( 数据 )相关的资源

            safeFail(promise, cause); //yangyc 通知 Promise 执行失败
            decrementPendingOutboundBytes(size, false, notifyWritability);  //yangyc 减少 totalPendingSize 计数
        }

        // recycle the entry
        e.recycle();   //yangyc 回收 Entry 对象

        return true; //yangyc 还有后续的 flush 的 Entry 节点
    }

    private void removeEntry(Entry e) { //yangyc 移除指定 Entry 对象
        if (-- flushed == 0) { //yangyc 已移除完已 flush 的 Entry 节点，置空 flushedEntry、tailEntry、unflushedEntry 。
            // processed everything
            flushedEntry = null;
            if (e == tailEntry) {
                tailEntry = null;
                unflushedEntry = null;
            }
        } else { //yangyc 未移除完已 flush 的 Entry 节点，flushedEntry 指向下一个 Entry 对象
            flushedEntry = e.next;
        }
    }

    /**
     * Removes the fully written entries and update the reader index of the partially written entry.
     * This operation assumes all messages in this buffer is {@link ByteBuf}.
     */
    public void removeBytes(long writtenBytes) {  //yangyc 将真正写入到 socket 写缓冲区的字节从出栈缓冲区移除； 参数：真正写入到 socket 写缓冲区的大小（可能是一条 buffer 的大小，可能是多条 buffer 的大小）
        for (;;) { //yangyc 循环移除
            Object msg = current();  //yangyc 获取 flushedEntry 节点执行的 entry.msg 数据
            if (!(msg instanceof ByteBuf)) {
                assert writtenBytes == 0;
                break;
            }

            final ByteBuf buf = (ByteBuf) msg;
            final int readerIndex = buf.readerIndex(); //yangyc 获得消息( 数据 )开始读取位置
            final int readableBytes = buf.writerIndex() - readerIndex;  //yangyc 计算出 msg 可读数据量大小

            if (readableBytes <= writtenBytes) {  //yangyc 条件成立：说明 unsafe 写入到 socket缓冲区的数据量 > flushedEntry.msg 可读数据量大小。则移除 flushedEntry 指向 entry
                if (writtenBytes != 0) {
                    progress(readableBytes); //yangyc 处理当前消息的 Entry 的写入进度
                    writtenBytes -= readableBytes; //yangyc 减小 writtenBytes
                }
                remove(); //yangyc 移除当前消息对应的 Entry
            } else { // readableBytes > writtenBytes //yangyc unsafe.write 真正写入socket缓冲区数据量 < 当前 flushedEntry.msg 可读数据量
                if (writtenBytes != 0) {
                    buf.readerIndex(readerIndex + (int) writtenBytes); //yangyc 标记当前消息的 ByteBuf 的读取位置
                    progress(writtenBytes); //yangyc 处理当前消息的 Entry 的写入进度
                }
                break;
            }
        }
        clearNioBuffers();  //yangyc 清除 NIO ByteBuff 数组的缓存
    }

    // Clear all ByteBuffer from the array so these can be GC'ed.
    // See https://github.com/netty/netty/issues/3837
    private void clearNioBuffers() { //yangyc 清除 NIO ByteBuff 数组的缓存
        int count = nioBufferCount;
        if (count > 0) {
            nioBufferCount = 0; //yangyc 归零 nioBufferCount
            Arrays.fill(NIO_BUFFERS.get(), 0, count, null); //yangyc 置空 NIO ByteBuf 数组
        }
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() { //yangyc 获得当前要写入到对端的 NIO ByteBuffer 数组，并且获得的数组大小不得超过 maxCount ，字节数不得超过 maxBytes
        return nioBuffers(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     * @param maxCount The maximum amount of buffers that will be added to the return value.
     * @param maxBytes A hint toward the maximum number of bytes to include as part of the return value. Note that this
     *                 value maybe exceeded because we make a best effort to include at least 1 {@link ByteBuffer}
     *                 in the return value to ensure write progress is made.
     */
    public ByteBuffer[] nioBuffers(int maxCount, long maxBytes) { //yangyc  将出栈缓冲区内的部分 entry.msg 转换成 JDK channel 依赖的标准对象 ByteBuffer； 返回 ByteBuffer 数组。 参数1：最多转换出1024个ByteBuffer对象，参数2:最多转换 maxBytes 字节的ByteBud
        assert maxCount > 0;
        assert maxBytes > 0;
        long nioBufferSize = 0; //yangyc 本次调用一共转换了多少容量的 buffer
        int nioBufferCount = 0; //yangyc 本次调用一共将 byteBuf 转换成多少 ByteBuffer 对象
        final InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();  //yangyc 获得当前线程的 NIO ByteBuffer 数组缓存
        ByteBuffer[] nioBuffers = NIO_BUFFERS.get(threadLocalMap); //yangyc 给每个线程分配一个长度为1024的byteBuffer数组，避免每次调用 nioBuffers 方法时，都创建 byteBuffer 数组
        Entry entry = flushedEntry;  //yangyc 从 flushedEntry 节点，开始向下遍历
        while (isFlushedEntry(entry) && entry.msg instanceof ByteBuf) { //yangyc 循环条件：当前节点不是null,并且当前节点不是 unflushedEntry 指向的节点 =》循环到末尾或者unflushedEntry就停止
            if (!entry.cancelled) { //yangyc 条件成立：当前 entry 节点没有取消，需要提取它的数据
                ByteBuf buf = (ByteBuf) entry.msg;
                final int readerIndex = buf.readerIndex();  //yangyc 获得消息( 数据 )开始读取位置
                final int readableBytes = buf.writerIndex() - readerIndex;  //yangyc 有效数据量

                if (readableBytes > 0) { //yangyc 有可读数据 --- msg 包含待发送数据...
                    if (maxBytes - readableBytes < nioBufferSize && nioBufferCount != 0) { //yangyc maxBytes<maxBytes+readableBytes 已转换的大小+本次转换的大小 > 最大限制，则跳出循环
                        // If the nioBufferSize + readableBytes will overflow maxBytes, and there is at least one entry
                        // we stop populate the ByteBuffer array. This is done for 2 reasons:
                        // 1. bsd/osx don't allow to write more bytes then Integer.MAX_VALUE with one writev(...) call
                        // and so will return 'EINVAL', which will raise an IOException. On Linux it may work depending
                        // on the architecture and kernel but to be safe we also enforce the limit here.
                        // 2. There is no sense in putting more data in the array than is likely to be accepted by the
                        // OS.
                        //
                        // See also:
                        // - https://www.freebsd.org/cgi/man.cgi?query=write&sektion=2
                        // - https://linux.die.net//man/2/writev
                        break;
                    }
                    nioBufferSize += readableBytes; //yangyc 更新总转换量； 原值 + 本条msg可读大小
                    int count = entry.count;  //yangyc 初始 Entry 节点的 NIO ByteBuffer 数量，默认值 -1
                    if (count == -1) { //yangyc 大概率条件成立
                        //noinspection ConstantValueVariableUse
                        entry.count = count = buf.nioBufferCount(); //yangyc 获取出byteBuf 底层到底是由多少 byteBuffer 构成，在这里都是 direct byteBuf. 正常是1； 特殊情况：CompositeByteBuf
                    }
                    int neededSpace = min(maxCount, nioBufferCount + count); //yangyc 计算出需要多大的 byteBuffer 数组
                    if (neededSpace > nioBuffers.length) {  //yangyc 需要的数组大小，超过 NIO ByteBuffer 数组的大小，进行扩容。
                        nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                        NIO_BUFFERS.set(threadLocalMap, nioBuffers);
                    }
                    if (count == 1) { //yangyc 正常情况 count==1
                        ByteBuffer nioBuf = entry.buf;
                        if (nioBuf == null) { //yangyc 条件一般会成立
                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                            // derived buffer yangyc 获取 byteBuf 底层真正内存对象 byteBuffer
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes); //yangyc 参数1：读锁引，参数2：可读容量大小
                        }
                        nioBuffers[nioBufferCount++] = nioBuf; //yangyc 将刚刚转换处理的 byteBuffer 对象加入到数组
                    } else {
                        // The code exists in an extra method to ensure the method is not too big to inline as this
                        // branch is not very likely to get hit very frequently.
                        nioBufferCount = nioBuffers(entry, buf, nioBuffers, nioBufferCount, maxCount);
                    }
                    if (nioBufferCount >= maxCount) {
                        break; //yangyc 到达 maxCount 上限，结束循环
                    }
                }
            }
            entry = entry.next; //yangyc 下一个 Entry节点
        }
        this.nioBufferCount = nioBufferCount;  //yangyc 出栈缓冲区记录有多少 byteBuffer 待出栈， 有多少字节 byteBuffer 待出栈
        this.nioBufferSize = nioBufferSize; //yangyc 设置 nioBufferSize 属性

        return nioBuffers; //yangyc 返回从 entry 链表中提出的 buffer 数组
    }

    private static int nioBuffers(Entry entry, ByteBuf buf, ByteBuffer[] nioBuffers, int nioBufferCount, int maxCount) {
        ByteBuffer[] nioBufs = entry.bufs;
        if (nioBufs == null) {
            // cached ByteBuffers as they may be expensive to create in terms
            // of Object allocation
            entry.bufs = nioBufs = buf.nioBuffers();
        }
        for (int i = 0; i < nioBufs.length && nioBufferCount < maxCount; ++i) {
            ByteBuffer nioBuf = nioBufs[i];
            if (nioBuf == null) {
                break;
            } else if (!nioBuf.hasRemaining()) {
                continue;
            }
            nioBuffers[nioBufferCount++] = nioBuf;
        }
        return nioBufferCount;
    }

    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) { //yangyc NIO ByteBuff 数组的扩容。
        int newCapacity = array.length; //yangyc 计算扩容后的数组的大小，按照 2 倍计算
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity]; //yangyc 创建新的 ByteBuffer 数组
        System.arraycopy(array, 0, newArray, 0, size);  //yangyc 复制老的 ByteBuffer 数组到新的 ByteBuffer 数组中

        return newArray;
    }

    /**
     * Returns the number of {@link ByteBuffer} that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public int nioBufferCount() {
        return nioBufferCount; //yangyc 返回 nioBufferCount 属性
    }

    /**
     * Returns the number of bytes that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public long nioBufferSize() {
        return nioBufferSize; //yangyc 返回 nioBufferSize 属性
    }

    /**
     * Returns {@code true} if and only if {@linkplain #totalPendingWriteBytes() the total number of pending bytes} did
     * not exceed the write watermark of the {@link Channel} and
     * no {@linkplain #setUserDefinedWritability(int, boolean) user-defined writability flag} has been set to
     * {@code false}.
     */
    public boolean isWritable() {
        return unwritable == 0; //yangyc unwritable>0: 则表示不可写;  unwritable=0: 则表示可写
    }

    /**
     * Returns {@code true} if and only if the user-defined writability flag at the specified index is set to
     * {@code true}.
     */
    public boolean getUserDefinedWritability(int index) { //yangyc 获得指定 bits 是否可写
        return (unwritable & writabilityMask(index)) == 0;
    }

    /**
     * Sets a user-defined writability flag at the specified index.
     */
    public void setUserDefinedWritability(int index, boolean writable) {
        if (writable) {
            setUserDefinedWritability(index);
        } else {
            clearUserDefinedWritability(index);
        }
    }

    private void setUserDefinedWritability(int index) {
        final int mask = ~writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private void clearUserDefinedWritability(int index) {
        final int mask = writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private static int writabilityMask(int index) {
        if (index < 1 || index > 31) {
            throw new IllegalArgumentException("index: " + index + " (expected: 1~31)");
        }
        return 1 << index;
    }

    private void setWritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & ~1; //yangyc 并位操作，修改第 0 位 bits 为 0
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {  //yangyc CAS 设置 unwritable 为新值
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(invokeLater);  //yangyc 若之前不可写，现在可写，触发 Channel WritabilityChanged 事件到 pipeline 中
                }
                break;
            }
        }
    }

    private void setUnwritable(boolean invokeLater) { //yangyc 如果累加完之后，超过出栈缓冲区高水位； 则设置 unwrieable 字段表示不可写； 并且向 pipeline 发起 unwrite 更改事件
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | 1; //yangyc 或位操作，修改第 0 位 bits 为 1
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0) {
                    fireChannelWritabilityChanged(invokeLater); //yangyc 若之前可写，现在不可写，触发 Channel WritabilityChanged 事件到 pipeline 中
                }
                break;
            }
        }
    }

    private void fireChannelWritabilityChanged(boolean invokeLater) { //yangyc 触发 Channel WritabilityChanged 事件到 pipeline 中
        final ChannelPipeline pipeline = channel.pipeline();
        if (invokeLater) { //yangyc 延迟执行，即提交 EventLoop 中触发 Channel WritabilityChanged 事件到 pipeline 中
            Runnable task = fireChannelWritabilityChangedTask;
            if (task == null) {
                fireChannelWritabilityChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelWritabilityChanged();
                    }
                };
            }
            channel.eventLoop().execute(task);
        } else {
            pipeline.fireChannelWritabilityChanged(); //yangyc 直接触发 Channel WritabilityChanged 事件到 pipeline 中
        }
    }

    /**
     * Returns the number of flushed messages in this {@link ChannelOutboundBuffer}.
     */
    public int size() {
        return flushed; //yangyc 获得 flushed 属性
    }

    /**
     * Returns {@code true} if there are flushed messages in this {@link ChannelOutboundBuffer} or {@code false}
     * otherwise.
     */
    public boolean isEmpty() {
        return flushed == 0; //yangyc 是否为空
    }

    void failFlushed(Throwable cause, boolean notify) { //yangyc 写入数据到对端失败，进行后续的处理
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return; //yangyc 正在通知 flush 失败中，直接返回
        }

        try {
            inFail = true;  //yangyc 标记正在通知 flush 失败中
            for (;;) { //yangyc 循环，移除所有已 flush 的 Entry 节点们
                if (!remove0(cause, notify)) {
                    break;
                }
            }
        } finally {
            inFail = false;  //yangyc 标记不在通知 flush 失败中
        }
    }

    void close(final Throwable cause, final boolean allowChannelOpen) {
        if (inFail) {  //yangyc 正在通知 flush 失败中
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause, allowChannelOpen); //yangyc 提交 EventLoop 的线程中，执行关闭
                }
            });
            return;
        }

        inFail = true; //yangyc 标记正在通知 flush 失败中

        if (!allowChannelOpen && channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        try {
            Entry e = unflushedEntry;  //yangyc 从 unflushedEntry 节点，开始向下遍历
            while (e != null) {
                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);  //yangyc 减少 totalPendingSize

                if (!e.cancelled) {
                    ReferenceCountUtil.safeRelease(e.msg); //yangyc 释放消息( 数据 )相关的资源
                    safeFail(e.promise, cause); //yangyc 通知 Promise 执行失败
                }
                e = e.recycleAndGetNext();  //yangyc 回收当前节点，并获得下一个 Entry 节点
            }
        } finally {
            inFail = false; //yangyc 标记在在通知 flush 失败中
        }
        clearNioBuffers();  //yangyc 清除 NIO ByteBuff 数组的缓存
    }

    void close(ClosedChannelException cause) {
        close(cause, false);
    }

    private static void safeSuccess(ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as trySuccess(...) is expected to return
        // false.
        PromiseNotificationUtil.trySuccess(promise, null, promise instanceof VoidChannelPromise ? null : logger);
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Deprecated
    public void recycle() {
        // NOOP
    }

    public long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     */
    public long bytesBeforeUnwritable() { //yangyc 获得距离不可写还有多少字节数
        long bytes = channel.config().getWriteBufferHighWaterMark() - totalPendingSize;
        // If bytes is negative we know we are not writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? bytes : 0; //yangyc 判断 #isWritable() 的原因是，可能已经被设置不可写
        }
        return 0;
    }

    /**
     * Get how many bytes must be drained from the underlying buffer until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    public long bytesBeforeWritable() { //yangyc 获得距离可写还要多少字节数
        long bytes = totalPendingSize - channel.config().getWriteBufferLowWaterMark();
        // If bytes is negative we know we are writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? 0 : bytes; //yangyc 判断 #isWritable() 的原因是，可能已经被设置不可写
        }
        return 0;
    }

    /**
     * Call {@link MessageProcessor#processMessage(Object)} for each flushed message
     * in this {@link ChannelOutboundBuffer} until {@link MessageProcessor#processMessage(Object)}
     * returns {@code false} or there are no more flushed messages to process.
     */
    public void forEachFlushedMessage(MessageProcessor processor) throws Exception {
        ObjectUtil.checkNotNull(processor, "processor");

        Entry entry = flushedEntry;
        if (entry == null) {
            return;
        }

        do {
            if (!entry.cancelled) {
                if (!processor.processMessage(entry.msg)) {
                    return;
                }
            }
            entry = entry.next;
        } while (isFlushedEntry(entry));
    }

    private boolean isFlushedEntry(Entry e) {
        return e != null && e != unflushedEntry;
    }

    public interface MessageProcessor {
        /**
         * Will be called for each flushed message until it either there are no more flushed messages or this
         * method returns {@code false}.
         */
        boolean processMessage(Object msg) throws Exception;
    }

    static final class Entry { //yangyc 在 write 操作时，将数据写到 ChannelOutboundBuffer 中，都会产生一个 Entry 对象
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @Override
            public Entry newObject(Handle<Entry> handle) { //yangyc Recycler 对象，用于重用 Entry 对象
                return new Entry(handle);
            }
        });

        private final Handle<Entry> handle; //yangyc Recycler 处理器, 归还 entry 到 objectPool
        Entry next; //yangyc 下一条 Entry, 通过它，形成 ChannelOutboundBuffer 内部的链式存储每条写入数据的数据结构
        Object msg; //yangyc 消息（数据），一般都是 ByteBuf 对象
        ByteBuffer[] bufs; //yangyc 当 Unsafe 调用出栈缓冲区 nioBuffers() 方法时，将entry中的msg转换成 ByteBuffer
        ByteBuffer buf; //yangyc 转化的 NIO ByteBuffer 对象
        ChannelPromise promise; //yangyc 业务层面关注 msg 写结果的提交的 Promise
        long progress; //yangyc 进度，已写入的字节数, 当数据写入成功后，可以通过它回调通知结果
        long total; //yangyc msg, byteBuf 有效数据量大小
        int pendingSize; //yangyc 每个 Entry 预计占用的内存大小，计算方式为消息( {@link #msg} )的字节数 + Entry 对象自身占用内存的大小（96： 16-object header; 6-reference fields; 2-long fields; 2 int fields; 1-boolean fields）
        int count = -1; //yangyc 当前 msg byteBuf 底层由多少 ByteBuffer 组成，一般都是1； 特殊情况：CompositeByteBuf 底层可以由多个 ByteBuf 组成
        boolean cancelled;  //yangyc 当前entry是否取消刷新到 socket, 默认 false

        private Entry(Handle<Entry> handle) {
            this.handle = handle;
        }

        static Entry newInstance(Object msg, int size, long total, ChannelPromise promise) { //yangyc 创建新 Entry 对象; 参数1：ByteBuf对象，并且归属于direct; 参数2：数据量大小；参数3：total(msg)==size; 参数4：本地写操作释放成功或失败的promise，可以注册监听事件
            Entry entry = RECYCLER.get();  //yangyc 通过 Recycler 重用对象； 从对象池中获取一个空闲的entry对象，如果对象池中没有空闲的entry,则new;
            entry.msg = msg; //yangyc 初始化属性
            entry.pendingSize = size + CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD;
            entry.total = total;
            entry.promise = promise;
            return entry;
        }

        int cancel() { //yangyc 标记 Entry 对象，取消写入到对端, 通过设置 canceled = true 来标记删除
            if (!cancelled) {
                cancelled = true; //yangyc 标记取消
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                ReferenceCountUtil.safeRelease(msg); //yangyc 释放消息( 数据 )相关的资源
                msg = Unpooled.EMPTY_BUFFER; //yangyc 设置为空 ByteBuf

                pendingSize = 0; //yangyc 置空属性
                total = 0;
                progress = 0;
                bufs = null;
                buf = null;
                return pSize; //yangyc 返回 pSize
            }
            return 0;
        }

        void recycle() { //yangyc 回收 Entry 对象，以为下次重用该对象
            next = null;
            bufs = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
            handle.recycle(this); //yangyc 回收 Entry 对象
        }

        Entry recycleAndGetNext() { //yangyc 获得下一个 Entry 对象，并回收当前 Entry 对象
            Entry next = this.next; // 获得下一个 Entry 对象
            recycle(); //yangyc 回收当前 Entry 对象
            return next; //yangyc 返回下一个 Entry 对象
        }
    }
}
