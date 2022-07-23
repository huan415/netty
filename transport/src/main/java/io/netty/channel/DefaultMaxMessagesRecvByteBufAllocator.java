/*
 * Copyright 2015 The Netty Project
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

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    private final boolean ignoreBytesRead;
    private volatile int maxMessagesPerRead;  //yangyc 每次读循环最大能读取的消息数量，每到 channel 内拉起一次数据成为一个消息
    private volatile boolean respectMaybeMoreData = true;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        this(maxMessagesPerRead, false);
    }

    DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead, boolean ignoreBytesRead) {
        this.ignoreBytesRead = ignoreBytesRead;
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @param respectMaybeMoreData
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @return
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        private ChannelConfig config; //yangyc channel#config
        private int maxMessagePerRead;  //yangyc 每次读循环最大能读取的消息数量，每到 channel 内拉起一次数据成为一个消息
        private int totalMessages; //yangyc 已读的消息数量
        private int totalBytesRead; //yangyc 已读的消息 size 总大小
        private int attemptedBytesRead; //yangyc 预估下次读的字节数
        private int lastBytesRead; //yangyc 最后一次读的字节数
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData; //yangyc true
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                return attemptedBytesRead == lastBytesRead; //yangyc 最后一次读取的字节数，是否等于，预估下次读的字节数。true:说明ch内可能还有剩余数据未读取完，还需继续读取。false:（1）评估数据量>剩余数据量，（2）ch 是close 状态，lastBytesRead=-1， 两种false情况都不需要继续读循环
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         */
        @Override
        public void reset(ChannelConfig config) { //yangyc 重置当前 Handle 对象
            this.config = config; //yangyc 重置 ChannelConfig 对象
            maxMessagePerRead = maxMessagesPerRead(); //yangyc 重置每次读循环最大能读取的消息数量，默认16
            totalMessages = totalBytesRead = 0; //yangyc 重置 totalMessages 和 totalBytesRead 属性
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) { //yangyc 参数 alloc: 才是真正分配内存的大佬
            return alloc.ioBuffer(guess()); //yangyc guess()=>根据读循环过程中上下文评估适合本地读的大小值，alloc.ioBuffer=>真正分配缓冲区对象
        }

        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        @Override
        public void lastBytesRead(int bytes) { //yangyc 设置最后读取字节数
            lastBytesRead = bytes; //yangyc 设置最后一次读取字节数
            if (bytes > 0) {
                totalBytesRead += bytes; //yangyc 总共读取字节数
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) { //yangyc continueReading 控制读循环是否结束（重要）
            return config.isAutoRead() && //yangyc 继续循环的四个条件：1.isAutoRead()默认true; //yangyc 【maybeMoreDataSupplier.get()】2. true:说明ch内可能还有剩余数据未读取完，还需继续读取。false:（1）评估数据量>剩余数据量，（2）ch 是close 状态，lastBytesRead=-1， 两种false情况都不需要继续读循环
                   (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&  //yangyc 3.【totalMessages < maxMessagePerRead】一次unSafe最多能从ch读取16次数据，不能超过16.
                   totalMessages < maxMessagePerRead && (ignoreBytesRead || totalBytesRead > 0); //yangyc 4.【totalBytesRead > 0】 客户端：正常情况下都是true（除非读取的数据量太多，超出int最大值），服务端：这里是0>0； 即：服务区每次 unsafe.read() 只进行一次读循环
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
