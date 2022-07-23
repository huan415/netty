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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;

    private static final int INDEX_INCREMENT = 4; //yangyc 索引增量 4
    private static final int INDEX_DECREMENT = 1; //yangyc 索引减量 1

    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i); //yangyc size 集合：16，32，64，48...496
        }

        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i); //yangyc 继续向数据添加 512，1024，2048...直到 int 值溢出
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i); //yangyc 从集合list复制到数组集合中
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) { //yangyc 二分查找算法
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {  //yangyc 参数1：64在SIZE_TABLE的下标, 参数2：65536在SIZE_TABLE的下标，参数3：2014
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial); //yangyc 计算 size 1024在SIZE_TABLE的下标
            nextReceiveBufferSize = SIZE_TABLE[index]; //yangyc nextReceiveBufferSize 表示下一次分配出来的 byteBuf 容量大小。默认第一次情况下，分配的byteBuf容量1024
        }

        @Override
        public void lastBytesRead(int bytes) { //yangyc 设置最后读取字节数
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            if (bytes == attemptedBytesRead()) { //yangyc 读取的数据量与评估量一致，说明ch内可能还有数据未读完...还需要继续
                record(bytes); //yangyc 更新 nextReceiveBufferSize 大小，因为前面评估的量被读取满了，意味者 ch 内有很多数据，需要更大的容器
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        private void record(int actualReadBytes) { //yangyc 参数：真实读取的数据量，本次从 ch 内读取的数据量
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) { //yangyc 举例：假设 SIZE_TABLE[idx]=512 => SIZE_TABLE[idx-1]=496; 如果本次读取的数据量<496,说明ch缓冲区内的数据不是很多，可能不需要那么大的 ByteBuf
                if (decreaseNow) {  //yangyc 第二次
                    index = max(index - INDEX_DECREMENT, minIndex); //yangyc 初始阶段定义过，最小不能小于 SIZE_TABLE[minIndex]
                    nextReceiveBufferSize = SIZE_TABLE[index]; //yangyc 获取相对减小的 BufferSize 值
                    decreaseNow = false; //yangyc 设置成false
                } else {
                    decreaseNow = true; //yangyc 第一次设置成 true
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) { //yangyc 说明本次 ch 读请求，已经将 ByteBuf 容器已经装满了，说明ch内可能还有很多很多的数据，所以这里让index右移一位，获取出来一个更大的 nextReceiveBufferSize，下次构建出更大的 ByteBuf 对象
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM); //yangyc 参数1：64, 参数2：1024，参数3：65536
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) { //yangyc 参数1：64, 参数2：1024，参数3：65536
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum); //yangyc 使用二分查找算法，获取 minimum size 在数组内的下标（SIZE_TABLE[下标] <= minimum 的值）
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1; //yangyc 因为小于 minimum, 所以右移 index
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);  //yangyc 使用二分查找算法，获取 maximum size 在数组内的下标（SIZE_TABLE[下标] >= maximum 的值）
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1; //yangyc 因为不能超出 maximum, 所以左移 index
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial; //yangyc 初始值 1024
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);  //yangyc 参数1：64在SIZE_TABLE的下标, 参数2：65536在SIZE_TABLE的下标，参数3：2014
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
