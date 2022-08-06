/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems; //yangyc 赋值之后不会再变化，表示当前 subpage 最多可给业务分配多少小块内存
    private int bitmapLength;
    private int nextAvail;
    private int numAvail; //yangyc 没对外划分出一小块内存后，该值都减一

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }
    //yangyc 参数1: arena范围内的pools符合当前规格的head节点;  参数2:当前chunk,因为subpage得知道自己的父亲是谁;  参数3:当前subpage占用的页子节点的id号;  参数4:当前页子节点管理的内存在整个内存的偏移位置;  参数5:8k;  参数6:16b 32b ...512b ..4096b;
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        //yangyc pageSize/16 是因为 tiny 最小规格 16b, 以最小规格划分的话，需要多少个 bit 才能表示这个内存的使用情况
        //yangyc /8 是因为 long 是8字节，而1字节是8bit ==> 计算出多少 long 才能表示整个位图
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64 yangyc 长度为 8 的 long 数组
        init(head, elemSize); //yangyc 参数1: arena范围内的pools符合当前规格的head节点; 参数2:16b 32b ...512b ..4096b;
    }

    void init(PoolSubpage<T> head, int elemSize) { //yangyc 参数1: arena范围内的pools符合当前规格的head节点; 参数2:16b 32b ...512b ..4096b;
        doNotDestroy = true; //yangyc true 表示当前 subpage 是存活状态。  false 表示当前 subpage 是释放状态--不可用
        this.elemSize = elemSize; //yangyc subpage 需要知道它管理的内存规格 size
        if (elemSize != 0) { //yangyc 条件一般成立
            maxNumElems = numAvail = pageSize / elemSize; //yangyc 计算出 subpage 按照 elemSize 划分， 一共可以划分多少小块。 例如:elemSize=32b ==> 256
            nextAvail = 0; //yangyc 申请内存时。 会使用整个字段，表示下一个可用的位置下标值
            bitmapLength = maxNumElems >>> 6; //yangyc maxNumElems/64 ==> 计算出当前 maxNumElems 需要 long 数组的多少 bit.   假设：maxNumElems=32b ==> bitmapLength=4    假设：maxNumElems=48b ==> bitmapLength=2
            if ((maxNumElems & 63) != 0) { //yangyc 条件成立：说明 maxNumElems 它不是一个 64 整除的数，需要 bitmapLength 再加一
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0; //yangyc 初始化 bitmap 的值，设置为 0
            }
        }
        addToPool(head); //yangyc 将当前新创建出来的 subpage 对象插入到 arena 范围的 pools 符合当前规格的 head 节点的下一个位置
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() { //yangyc 申请小规格内存入口
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) { //yangyc 条件1成立: 说明当前subpage管理的一页内存全部都划分出去了, 没有空闲内存。条件2成立: doNotDestroy初始为true, 当subpage释放内存后被改成false
            return -1;
        }

        final int bitmapIdx = getNextAvail(); //yangyc 从 bitmap 内查找一个可用的 bit, 返回该 bit 的索引值
        //yangyc 下面逻辑， 将 bitmapIdx 表示的 bit 设置未1， 表示这快小内存已经被划分出去了
        int q = bitmapIdx >>> 6; //yangyc bitmap[q]
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r;

        if (-- numAvail == 0) { //yangyc 因为刚刚划分出去了一小块，所以减一，减一之后如果是0, 说明当前这个 subpage 管理的这块 page 完全划分完了
            removeFromPool(); //yangyc 从 arena 内移除，因为当前 subpage 管理的内存完全划分完了
        }

        return toHandle(bitmapIdx); //yangyc 参数: 业务划分出去的内存对应位图的索引值
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) { //yangyc 释放内存，参数1:arena 范围该内存规格在 pools 的head节点; 参数2:计算出内存占用位图的真实索引;
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail; //yangyc nextAvail 初始值是0，当某个byteBuf占用的内存还给当前subpage时，这个内存占用的bit的索引值会赋值给 nextAvail， 下次再申请直接就使用 nextAvail 就可以了
        if (nextAvail >= 0) { //yangyc 情况1：初始值 nextAvail=0。 情况2：其他 byteBuf 归还内存时，会设置 nextAvail 为它占用的那块贵阳的 bit 索引值
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();//yangyc 其他情况走这里
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) { //yangyc 参数1：i  参数2：bitmap[i] 表示的这一小块 bitmap
        final int maxNumElems = this.maxNumElems; //yangyc 当前 subpage 最多可对外分配的内存快数。假设当前规格为32b时,则maxNumElems=256
        final int baseVal = i << 6; //yangyc i左移6位，==> i*64

        for (int j = 0; j < 64; j ++) { //yangyc for 循环需要从 bitmap[i] 中找到第一个可以用的 bit 位置，返回给业务
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) { //yangyc 参数: 业务划分出去的内存对应位图的索引值
        //yangyc bitmapIdx << 32 | memoryMapIdx  ==>   高 32 位是业务占用 subpage 内存 bit 索引值，低 32 位是当前 subpage 占用 chunk 叶子节点的 id 值
        //yangyc 0x4000000000000000L 是除符号位之外，最高位为1，其他位为0。为什么这么做？因为后面逻辑需要根据 handler 值创建 bytebuf 对象，需要根据 handle 计算出 bytebuf 共享 chunk ByteBuffer 内存的偏移位置
        //yangyc allocateRun 和 allocateSubpage 申请内存，计算规则完全不一致，需要根据handler的标记进行区分，当 subpage 第一次对外分配内存时，返回的 handle 如果没有标记位的话。会与allocate产生冲突
        //yangyc 例如：subpage 占用的页子节点是2048，第一次对外分配的值为：高32是0，低32位是2048， 如果没有标记位就和allocate冲突
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx; //yangyc memoryMapIdx 是当前 subpage 占用 chunk 的叶子节点的 ID 值
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
