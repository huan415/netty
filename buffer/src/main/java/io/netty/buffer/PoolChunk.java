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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;
    final T memory;
    final boolean unpooled;
    final int offset;
    private final byte[] memoryMap;
    private final byte[] depthMap;
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;
    private final int maxOrder;
    private final int chunkSize;
    private final int log2ChunkSize;
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    private int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;
    //yangyc 参数1:当前 directArena 对象; 参数2:DirectByteBuffer实例; 参数3:8K; 参数4:满二叉树深度11; 参数5:13--1左移13位得到pageSize; 参数6:16mb; 参数7:0;
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory; //yangyc 让 chunk 直接持有 byteBuffer 对象
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1); //yangyc chunk 使用一颗满二叉树表示内存占用情况, 二叉树中每个节点有三个维度的数据（深度, ID, 可分配的深度值--初始值与深度值一样）。
        //yangyc 当某个节点上的内存被分配出去后,它的可分配深度需要改成 unusable. 以后就可以再从这个节点上分配内存了
        log2ChunkSize = log2(chunkSize); //yangyc 24
        subpageOverflowMask = ~(pageSize - 1); //yangyc 作用: 当从 chunk 申请 >= 1page 时, 申请的容量与subpageOverflowMask进行位运算会得到一个非0值
        freeBytes = chunkSize; //yangyc 初始时, 默认 16mb

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder; //yangyc 最多可申请 2048 个 subpage

        // Generate the memory map. //yangyc 使用一个数组表示满二叉树, 公式: i的左节点--2i; i的右节点--2i+1
        memoryMap = new byte[maxSubpageAllocs << 1]; //yangyc 创建一个长度为 4096 的数组。数组的每个元素表示当前 i 下标的树节点的可分配深度能力值, 每个树节点的可分配深度能力值为深度值,
        //yangyc 例如: memoryMap[1]=0 表示根节点可分配整个内存; memoryMap[1]=1 表示根节点可分配整个内存的一半; 注意: memoryMap 对应索引的树节点,管理的内存被划分出去后,值会改变
        depthMap = new byte[memoryMap.length]; //yangyc 表示当前索引的树节点, 所在的树深度是多少。注意: 初始化之后 depthMap 就不会再发送改变了
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs); //yangyc 因为 chunk 默认情况下, 最多可以开辟 2048 个 subpage。所以这里创建一个长度为2048的subpage数组
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) { //yangyc 参数1:返回给业务使用的ByteBuf对象; 参数2:业务层需要的内存容量; 参数3:将 req 转换成规格大小
        final long handle;
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize yangyc 条件成立: 说明 normCapacity 是一个 >= pageSize 的值(8K 16K 32K...<=16mb 逻辑的入口), 也就是业务需求的内存量是一个大于等于一个页的内存
            handle =  allocateRun(normCapacity); //yangyc 这里的 handle 是当前分配内存占用的树节点 id 值, 可能是 -1：表示申请失败
        } else { //yangyc else 说明需要的内存是小规格类型的, tiny、small
            handle = allocateSubpage(normCapacity);
        }
        //yangyc 执行到这里会拿到一个 handle 值，该值可能是 allocateRun 也可能是 allocateSubpage 的返回值，
        if (handle < 0) {
            return false; //yangyc handle==-1, 说明 allocateRun 或 allocateSubpage 申请内存失败
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity); //yangyc 参数1: buf 返回给业务使用 ByteBuf 对象，下面逻辑会给buf分配内存对象，参数2：null; 参数3：allocateRun或allocateSubpage 返回值; 参数4：业务层需要的内存容量;
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) { //yangyc 比如: id=2048, 会影响 1024、512、256、128、64、32、16、8、4、2、1
        while (id > 1) {
            int parentId = id >>> 1; //yangyc 2048>>>1 ==> 1024
            byte val1 = value(id); //yangyc 获取 1024 节点 左右节点的能力值。  2048:12    2049:11
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2; //yangyc 获取出左右子节点 val 较小的值
            setValue(parentId, val); //yangyc 将父节点设置为较小的 val
            id = parentId; //yangyc 更新 id 为父节点, 循环向上更新
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) { //yangyc 到满二叉树去分配内存。参数1: 内存容量大小对应的深度值
        int id = 1; //yangyc id为1的节点是根节点, allocateNode 方法是从根节点开始向下查找一个合适深度 d 的节点进行占用, 完成内存分配。
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id); //yangyc 获取 chunk 二叉树根节点可分配深度能力值
        if (val > d) { // unusable
            return -1; //yangyc 条件成立: 说明当前 chunk 剩余内存不足以支撑内次内存申请。返回-1--表示内存申请失败
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0 yangyc 条件1:val表示二叉树中某个节点的可分配深度能力值。val<d 说明当前节点管理的内存比较大, 大于申请容量，需要到当前节点的下一级尝试申请
            id <<= 1; //yangc 向下一级。比如: id=1, id << 1 ==> 2
            val = value(id);
            if (val > d) {
                id ^= 1; //yangyc 伙伴算法--获取兄弟节点 id
                val = value(id); //yangyc 获取兄弟节点可分配深度能力值
            }
        }
        byte value = value(id); //yangyc 获取当前节点可分配深度能力值 -- 合适的分配节点能力值
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable yangyc 占用整个节点, 将这个节点的分配能力值改成 unusable
        updateParentsAlloc(id); //yangyc 因为当前节点的内存被分配出去, 影响了当前节点的父节点、爷爷节点... 需要更新分配的深度能力值
        return id; //yangyc 返回分配给用户内存占用的 node 节点 id
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) { //yangyc normCapacity 规格后的大小 8K、16K ...
        int d = maxOrder - (log2(normCapacity) - pageShifts); //yangyc 计算 normCapacity 容量大小的内存需要到深度为 d 的节点上去分配内存
        int id = allocateNode(d); //yangyc 到满二叉树去分配内存。参数1: 内存容量大小对应的深度值
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity yangyc 说明需要的内存是小规格类型的, tiny、small.   capacity 16b 32b 48b ... 512b 1024b 2028b 4096b
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity); //yangyc 获得符合当前规格大小的 head 节点. 参数1: 规格后的 elemSize
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves yangyc d=11; 因为接下来要从 chunk 上申请一页内存。满二叉树页子节点管理一个页，所以d设置成maxOrder
        synchronized (head) { //yangyc 锁 head 节点
            int id = allocateNode(d); //yangyc 根据传入的深度值，占用一个树节点，这里传入的深度值参数d=11，是页子节点的深度值，也就是申请一个页。返回页子深度值的id 或者-1
            if (id < 0) { //yangyc id=-1, 说明 chunk 连一个页也分配不了了
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages; //yangyc subpages 是一个长度为 2048 的数组，保证 chunk 创建出来的 subpage 都有地方存数
            final int pageSize = this.pageSize;

            freeBytes -= pageSize; //yangyc 占用了一个页，需要从 chunk 减去 8K

            int subpageIdx = subpageIdx(id); //yangyc 取模算法
            PoolSubpage<T> subpage = subpages[subpageIdx]; //yangyc 获取出当前位置的 subpage
            if (subpage == null) { //yangyc 正常情况，subpage == null
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity); //yangyc 参数1: arena范围内的pools符合当前规格的head节点;  参数2:当前chunk,因为subpage得知道自己的父亲是谁;  参数3:当前subpage占用的页子节点的id号;  参数4:当前页子节点管理的内存在整个内存的偏移位置;  参数5:8k;  参数6:16b 32b ...512b ..4096b;
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            return subpage.allocate(); //yangyc 申请小规格内存入口
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        int memoryMapIdx = memoryMapIdx(handle); //yangyc 计算出待归还内存占用树节点 ID
        int bitmapIdx = bitmapIdx(handle); //yangyc 计算出带归还占用的 bit 索引值

        if (bitmapIdx != 0) { // free a subpage yangyc 条件成立：说明待缓存内存的规格是：tiny 或 small
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)]; //yangyc 根据 handler 计算出来的 subpage 占用页子节点ID 获取出来 subpage 对象
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize); //yangyc 获取出 arena 范围该内存规格在 pools 的head节点
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) { //yangyc 释放内存，参数1:arena 范围该内存规格在 pools 的head节点; 参数2:计算出内存占用位图的真实索引;
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }
    //yangyc 参数1: buf 返回给业务使用 ByteBuf 对象，下面逻辑会给buf分配内存对象，参数2：null; 参数3：allocateRun或allocateSubpage 返回值; 参数4：业务层需要的内存容量;
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle); //yangyc 获取 handle 低32位的值。 无论allocateRun还是allocateSubpage返回的低32位都表示树节点的id值
        int bitmapIdx = bitmapIdx(handle); //yangyc  获取 handle 高32位的值。allocateRun高32是0，allocateSubpage高32是1，
        if (bitmapIdx == 0) { //yangyc 条件成立：说明 handle 表示的是 allocateRun 的返回值，接下来出来 allocateRun 内存的封装逻辑
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset, //yangyc 参数1：创建buteBuf分配内存的chunk对象。真实的内存是chunk持有的，所以必传chunk,参数2：null; 参数3：handle是buf占用内存的位置相关信息，后面释放内存也要使用hanlde; 参数4：计算出当前buf占用的内存在byteBuffer上便宜位置，必须知道管理的内存在大内存上的便宜位置;
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache()); //yangyc  参数5：赋值给buf length属性,表示业务申请内存大小; 参数6：比如：memoryMapIdx=2048返回8k  memoryMapIdx=1024返回16k  赋值给buf maxLength, 表示 bug 可用内存的最大大小; 参数7：当前线程的 threaLocalCache对象，为什么要这个对象？因为释放的时候首选的地方为 threadLocache, 缓存到线程局部，方便后面再申请时使用，而不是直接归还到 pool;
        } else { //yangyc 条件成立：说明 handle 表示的是 allocateSubpage 的返回值，接下来出来 allocateSubpage 内存的封装逻辑
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity); //yangyc 参数1:返回给业务使用的 ByteBuf对象，它包装内存; 参数2:可能是null; 参数3:handle是buf占用内存的位置相关信息，后面释放内存也要使用hanlde; 参数4:从高位起第二位是标记位,为1; 参数5:赋值给buf length属性,表示业务申请内存大小;
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }
    //yangyc 参数1:返回给业务使用的 ByteBuf对象，它包装内存; 参数2:可能是null; 参数3:handle是buf占用内存的位置相关信息，后面释放内存也要使用hanlde; 参数4:从高位起第二位是标记位,为1; 参数5:赋值给buf length属性,表示业务申请内存大小;
    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle); //yangyc 从 subpage 占用页子节点的 id

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)]; //yangyc 获取出给 bud 分配内存的 subpage 对象
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init( //yangyc 参数1:创建buteBuf分配内存的chunk对象。真实的内存是chunk持有的，所以必传chunk,; 参数2:可能是null; 参数3:handle是buf占用内存的位置相关信息，后面释放内存也要使用hanlde;参数4: 两个加起来就是业务申请的这一小块内存在byteBuffer大内存上的正确偏移位置;参数5:赋值给buf length属性,表示业务申请内存大小;参数6:规格大小;参数7:当前线程的 threaLocalCache对象，为什么要这个对象？因为释放的时候首选的地方为 threadLocache, 缓存到线程局部，方便后面再申请时使用，而不是直接归还到 pool;
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset, //yangyc 1.runOffset(memoryMapIdx)==>计算出 subpage 在 chunk 上便宜位置。 2.(bitmapIdx & 0x3FFFFFFF)*subpage.elemSize==> 计算出 bitmapIdx在subpage上的偏移位置。 两个加起来就是业务申请的这一小块内存在byteBuffer大内存上的正确偏移位置
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) { //yangyc 计算当前页子节点管理的内存在整个内存的偏移位置
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id); //yangyc 假设 id=2049 ==> depth(id)=11       id=2048 ==> shift=0.   id=2049 ==> shift=1   id=2050 ==> shift=2
        return shift * runLength(id);//yangyc id=2048 ==> 返回:offset=0.   id=2049 ==> 返回:offset=8k   id=2050 ==> 返回:offset=16k  id=2051 ==> 返回:offset=32k
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
