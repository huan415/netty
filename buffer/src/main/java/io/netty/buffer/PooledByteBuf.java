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

package io.netty.buffer;

import io.netty.util.internal.ObjectPool.Handle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf { //yangyc 基于对象池的 ByteBuf 实现类，提供公用的方法

    private final Handle<PooledByteBuf<T>> recyclerHandle; //yangyc Recycler 处理器，用于回收对象

    protected PoolChunk<T> chunk; //yangyc 使用 Jemalloc 算法管理内存，而 Chunk 是里面的一种内存块
    protected long handle; //yangyc  从 Chunk 对象中分配的内存块所处的位置
    protected T memory; //yangyc  内存空间。具体什么样的数据，通过子类设置泛型
    protected int offset; //yangyc memory 开始位置
    protected int length; //yangyc 目前使用 memory 的长度( 大小 )
    int maxLength; //yangyc 在写入数据超过 maxLength 容量时，会进行扩容，但是容量的上限，为 maxCapacity
    PoolThreadCache cache;
    ByteBuffer tmpNioBuf; //yangyc 临时 ByteBuff 对象
    private ByteBufAllocator allocator; //yangyc ByteBuf 分配器对象

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer, //yangyc 基于 pooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    void initUnpooled(PoolChunk<T> chunk, int length) { //yangyc 基于 unPoolooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
        init0(chunk, null, 0, 0, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer, //yangyc 初始化 PooledByteBuf 对象
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;
        assert !PoolChunk.isSubpage(handle) || chunk.arena.size2SizeIdx(maxLength) <= chunk.arena.smallMaxSizeIdx:
                "Allocated small sub-page handle for a buffer size that isn't \"small.\"";

        chunk.incrementPinnedMemory(maxLength);
        this.chunk = chunk;
        memory = chunk.memory;
        tmpNioBuf = nioBuffer;
        allocator = chunk.arena.parent;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     */
    final void reuse(int maxCapacity) { //yangyc 每次在重用 PooledByteBuf 对象时，需要调用该方法，重置属性
        maxCapacity(maxCapacity);  //yangyc 设置最大容量
        resetRefCnt();  //yangyc 设置引用数量为 0
        setIndex0(0, 0); //yangyc 重置读写索引为 0
        discardMarks(); //yangyc 重置读写标记位为 0
    }

    @Override
    public final int capacity() { //yangyc 获得当前容量
        return length;
    }

    @Override
    public int maxFastWritableBytes() {
        return Math.min(maxLength, maxCapacity()) - writerIndex;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) { //yangyc 调整容量大小, 可能对 memory 扩容或缩容
        if (newCapacity == length) {
            ensureAccessible();
            return this;
        }
        checkNewCapacity(newCapacity);  //yangyc 校验新的容量，不能超过最大容量
        if (!chunk.unpooled) {  //yangyc Chunk 内存，是池化
            // If the request capacity does not require reallocation, just update the length of the memory.
            if (newCapacity > length) { //yangyc 新容量大于当前容量
                if (newCapacity <= maxLength) { //yangyc 小于 memory 最大容量
                    length = newCapacity; //yangyc 新容量大于当前容量，但是小于 memory 最大容量，仅仅修改当前容量，无需进行扩容
                    return this;
                }
            } else if (newCapacity > maxLength >>> 1 && //yangyc 小于 memory 最大容量的一半
                    (maxLength > 512 || newCapacity > maxLength - 16)) { //yangyc SubPage 最小是 16 B ，如果小于等 16 ，无法缩容
                // here newCapacity < length
                length = newCapacity;
                trimIndicesToCapacity(newCapacity);
                return this;
            }
        }

        // Reallocation required.
        chunk.decrementPinnedMemory(maxLength);
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;  //yangyc 统一大端模式: 返回字节序为 ByteOrder.BIG_ENDIAN
    }

    @Override
    public final ByteBuf unwrap() {
        return null; //yangyc 返回空，因为没有被装饰的 ByteBuffer 对象v
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex()); //yangyc ，创建池化的 PooledDuplicatedByteBuf.newInstance 对象
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length); //yangyc 创建池化的 PooledSlicedByteBuf 对象
    }

    protected final ByteBuffer internalNioBuffer() { //yangyc 获得临时 ByteBuf 对象( tmpNioBuf )
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory); //yangyc 为空，创建临时 ByteBuf 对象
        } else {
            tmpNioBuf.clear();
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    @Override
    protected final void deallocate() { //yangyc 当引用计数为 0 时，调用该方法，进行内存回收
        if (handle >= 0) {
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            chunk.decrementPinnedMemory(maxLength);
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);  //yangyc 释放内存回 Arena 中
            tmpNioBuf = null;
            chunk = null;
            recycle(); //yangyc 回收对象
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this); //yangyc 回收对象
    }

    protected final int idx(int index) {
        return offset + index; //yangyc 获得指定位置在 memory 变量中的位置
    }

    final ByteBuffer _internalNioBuffer(int index, int length, boolean duplicate) { //yangyc 参数1：读锁引，参数2：可读容量大小
        index = idx(index); //yangyc 添加偏移量后的 index
        ByteBuffer buffer = duplicate ? newInternalNioBuffer(memory) : internalNioBuffer(); //yangyc buffer 与 memory 指向同一块空间
        buffer.limit(index + length).position(index); //yangyc 设置 buffer limit; 范围为 byteBuf 在共享 menory 的最大下标， positiion 设置为添加偏移后的读锁引
        return buffer;
    }

    ByteBuffer duplicateInternalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return _internalNioBuffer(index, length, true);
    }

    @Override
    public final ByteBuffer internalNioBuffer(int index, int length) { //yangyc 参数1：读锁引，参数2：可读容量大小
        checkIndex(index, length);
        return _internalNioBuffer(index, length, false);
    }

    @Override
    public final int nioBufferCount() {
        return 1;
    }

    @Override
    public final ByteBuffer nioBuffer(int index, int length) {
        return duplicateInternalNioBuffer(index, length).slice();
    }

    @Override
    public final ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    @Override
    public final boolean isContiguous() {
        return true;
    }

    @Override
    public final int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length));
    }

    @Override
    public final int readBytes(GatheringByteChannel out, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false));
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length), position);
    }

    @Override
    public final int readBytes(FileChannel out, long position, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false), position);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        try {
            return in.read(internalNioBuffer(index, length)); //yangyc 读取数据到临时的 Java NIO ByteBuffer 中
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public final int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        try {
            return in.read(internalNioBuffer(index, length), position);
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }
}
