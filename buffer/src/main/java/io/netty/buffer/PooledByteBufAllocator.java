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

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PooledByteBufAllocator.class);
    private static final int DEFAULT_NUM_HEAP_ARENA;
    private static final int DEFAULT_NUM_DIRECT_ARENA;

    private static final int DEFAULT_PAGE_SIZE;
    private static final int DEFAULT_MAX_ORDER; // 8192 << 11 = 16 MiB per chunk
    private static final int DEFAULT_TINY_CACHE_SIZE;
    private static final int DEFAULT_SMALL_CACHE_SIZE;
    private static final int DEFAULT_NORMAL_CACHE_SIZE;
    private static final int DEFAULT_MAX_CACHED_BUFFER_CAPACITY;
    private static final int DEFAULT_CACHE_TRIM_INTERVAL;
    private static final long DEFAULT_CACHE_TRIM_INTERVAL_MILLIS;
    private static final boolean DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    private static final int DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT;
    static final int DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK;

    private static final int MIN_PAGE_SIZE = 4096;
    private static final int MAX_CHUNK_SIZE = (int) (((long) Integer.MAX_VALUE + 1) / 2);

    private final Runnable trimTask = new Runnable() {
        @Override
        public void run() {
            PooledByteBufAllocator.this.trimCurrentThreadCache();
        }
    };

    static {
        int defaultPageSize = SystemPropertyUtil.getInt("io.netty.allocator.pageSize", 8192); //yangyc 默认一页内存大小 8K
        Throwable pageSizeFallbackCause = null;
        try {
            validateAndCalculatePageShifts(defaultPageSize);
        } catch (Throwable t) {
            pageSizeFallbackCause = t;
            defaultPageSize = 8192;
        }
        DEFAULT_PAGE_SIZE = defaultPageSize; //yangyc 设置常量 DEFAULT_PAGE_SIZE 为 8K

        int defaultMaxOrder = SystemPropertyUtil.getInt("io.netty.allocator.maxOrder", 11); //yangyc PoolChunk 内部使用的一颗满二叉树，表示内存占用情况, 树最深是11
        Throwable maxOrderFallbackCause = null;
        try {
            validateAndCalculateChunkSize(DEFAULT_PAGE_SIZE, defaultMaxOrder);
        } catch (Throwable t) {
            maxOrderFallbackCause = t;
            defaultMaxOrder = 11;
        }
        DEFAULT_MAX_ORDER = defaultMaxOrder; //yangyc 设置常量 DEFAULT_MAX_ORDER 为 11

        // Determine reasonable default for nHeapArena and nDirectArena.
        // Assuming each arena has 3 chunks, the pool should not consume more than 50% of max memory.
        final Runtime runtime = Runtime.getRuntime();

        /*
         * We use 2 * available processors by default to reduce contention as we use 2 * available processors for the
         * number of EventLoops in NIO and EPOLL as well. If we choose a smaller number we will run into hot spots as
         * allocation and de-allocation needs to be synchronized on the PoolArena.
         *
         * See https://github.com/netty/netty/issues/3888.
         */
        final int defaultMinNumArena = NettyRuntime.availableProcessors() * 2; //yangyc 默认最少 arena 最少格式：2*CPU
        final int defaultChunkSize = DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER; //yangyc 8K 左移11位 ==> 16mb； 即：默认一个 chunk 管理16mb的真实内存
        DEFAULT_NUM_HEAP_ARENA = Math.max(0,  //yangyc heapArena 个数：2*CPU
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numHeapArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                runtime.maxMemory() / defaultChunkSize / 2 / 3)));
        DEFAULT_NUM_DIRECT_ARENA = Math.max(0,  //yangyc directArena 个数：2*CPU
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numDirectArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                PlatformDependent.maxDirectMemory() / defaultChunkSize / 2 / 3)));

        // cache sizes
        DEFAULT_TINY_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.tinyCacheSize", 512);   //yangyc TinyMemoryRegionCache 内部可以缓存(512)个内存信息。
        DEFAULT_SMALL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.smallCacheSize", 256); //yangyc SmallMemoryRegionCache 内部可以缓存(256)个内存信息。
        DEFAULT_NORMAL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.normalCacheSize", 64);//yangyc NormalMemoryRegionCache 内部可以缓存(64)个内存信息。

        // 32 kb is the default maximum capacity of the cached buffer. Similar to what is explained in
        // 'Scalable memory allocation using jemalloc'
        DEFAULT_MAX_CACHED_BUFFER_CAPACITY = SystemPropertyUtil.getInt( //yangyc 32K, 表示MemoryRegionCache最大缓存内存规格是: 32K
                "io.netty.allocator.maxCachedBufferCapacity", 32 * 1024);

        // the number of threshold of allocations when cached entries will be freed up if not frequently used
        DEFAULT_CACHE_TRIM_INTERVAL = SystemPropertyUtil.getInt(
                "io.netty.allocator.cacheTrimInterval", 8192);

        DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                "io.netty.allocation.cacheTrimIntervalMillis", 0);

        DEFAULT_USE_CACHE_FOR_ALL_THREADS = SystemPropertyUtil.getBoolean( //yangyc 是否全部的线程都使用 PoolThreadCache 技术, 默认true, 表示都使用
                "io.netty.allocator.useCacheForAllThreads", true);

        DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT = SystemPropertyUtil.getInt(
                "io.netty.allocator.directMemoryCacheAlignment", 0);

        // Use 1023 by default as we use an ArrayDeque as backing storage which will then allocate an internal array
        // of 1024 elements. Otherwise we would allocate 2048 and only use 1024 which is wasteful.
        DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK = SystemPropertyUtil.getInt(
                "io.netty.allocator.maxCachedByteBuffersPerChunk", 1023);

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.allocator.numHeapArenas: {}", DEFAULT_NUM_HEAP_ARENA);
            logger.debug("-Dio.netty.allocator.numDirectArenas: {}", DEFAULT_NUM_DIRECT_ARENA);
            if (pageSizeFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE);
            } else {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE, pageSizeFallbackCause);
            }
            if (maxOrderFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER);
            } else {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER, maxOrderFallbackCause);
            }
            logger.debug("-Dio.netty.allocator.chunkSize: {}", DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER);
            logger.debug("-Dio.netty.allocator.tinyCacheSize: {}", DEFAULT_TINY_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.smallCacheSize: {}", DEFAULT_SMALL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.normalCacheSize: {}", DEFAULT_NORMAL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.maxCachedBufferCapacity: {}", DEFAULT_MAX_CACHED_BUFFER_CAPACITY);
            logger.debug("-Dio.netty.allocator.cacheTrimInterval: {}", DEFAULT_CACHE_TRIM_INTERVAL);
            logger.debug("-Dio.netty.allocator.cacheTrimIntervalMillis: {}", DEFAULT_CACHE_TRIM_INTERVAL_MILLIS);
            logger.debug("-Dio.netty.allocator.useCacheForAllThreads: {}", DEFAULT_USE_CACHE_FOR_ALL_THREADS);
            logger.debug("-Dio.netty.allocator.maxCachedByteBuffersPerChunk: {}",
                    DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK);
        }
    }

    public static final PooledByteBufAllocator DEFAULT =
            new PooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    private final PoolArena<byte[]>[] heapArenas;
    private final PoolArena<ByteBuffer>[] directArenas;
    private final int tinyCacheSize;
    private final int smallCacheSize;
    private final int normalCacheSize;
    private final List<PoolArenaMetric> heapArenaMetrics;
    private final List<PoolArenaMetric> directArenaMetrics;
    private final PoolThreadLocalCache threadCache; //yangyc 类似于 ThreadLocal 对象, 每个线程到 threadCache 内可以获取到一个当前线程自己的 PoolThreadCache 对象
    private final int chunkSize;
    private final PooledByteBufAllocatorMetric metric;

    public PooledByteBufAllocator() {
        this(false);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(boolean preferDirect) { //yangyc 参数1：一般是 true; 表示偏向使用堆外内存; 参数2：heapArena个数--2*CPU; 参数3：directArena个数--2*CPU; 参数4：默认页大小 8K; 参数5：PoolChunk 内部使用的一颗满二叉树，表示内存占用情况, 树最深是11;
        this(preferDirect, DEFAULT_NUM_HEAP_ARENA, DEFAULT_NUM_DIRECT_ARENA, DEFAULT_PAGE_SIZE, DEFAULT_MAX_ORDER);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(false, nHeapArena, nDirectArena, pageSize, maxOrder);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, int, boolean)}
     */
    @Deprecated //yangyc 参数1：一般是 true; 表示偏向使用堆外内存; 参数2：heapArena个数--2*CPU; 参数3：directArena个数--2*CPU; 参数4：默认页大小 8K; 参数5：PoolChunk 内部使用的一颗满二叉树，表示内存占用情况, 树最深是11;
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,//yangyc 参数1：一般是 true; 表示偏向使用堆外内存; 参数2：heapArena个数--2*CPU; 参数3：directArena个数--2*CPU; 参数4：默认页大小 8K; 参数5：PoolChunk 内部使用的一颗满二叉树，表示内存占用情况, 树最深是11;
                DEFAULT_TINY_CACHE_SIZE, DEFAULT_SMALL_CACHE_SIZE, DEFAULT_NORMAL_CACHE_SIZE); //yangyc 参数6：TinyMemoryRegionCache 内部可以缓存(512)个内存信息。;参数7：SmallMemoryRegionCache 内部可以缓存(256)个内存信息。;参数8：NormalMemoryRegionCache 内部可以缓存(64)个内存信息。;
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, int, boolean)}
     */
    @Deprecated //yangyc 参数1：一般是 true; 表示偏向使用堆外内存; 参数2：heapArena个数--2*CPU; 参数3：directArena个数--2*CPU; 参数4：默认页大小 8K; 参数5：PoolChunk 内部使用的一颗满二叉树，表示内存占用情况, 树最深是11; 参数6：TinyMemoryRegionCache 内部可以缓存(512)个内存信息。;参数7：SmallMemoryRegionCache 内部可以缓存(256)个内存信息。;参数8：NormalMemoryRegionCache 内部可以缓存(64)个内存信息。;
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder, tinyCacheSize, smallCacheSize,
                normalCacheSize, DEFAULT_USE_CACHE_FOR_ALL_THREADS, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT); //yangyc 参数9：是否全部的线程都使用 PoolThreadCache 技术, 默认true, 表示都使用; 参数10：对齐填充,0值;
    }

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder, int tinyCacheSize,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
                tinyCacheSize, smallCacheSize, normalCacheSize,
                useCacheForAllThreads, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }
    //yangyc 参数1：一般是 true; 表示偏向使用堆外内存; 参数2：heapArena个数--2*CPU; 参数3：directArena个数--2*CPU; 参数4：默认页大小 8K; 参数5：PoolChunk 内部使用的一颗满二叉树，表示内存占用情况, 树最深是11;
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize, //yangyc 参数6：TinyMemoryRegionCache 内部可以缓存(512)个内存信息。;参数7：SmallMemoryRegionCache 内部可以缓存(256)个内存信息。;参数8：NormalMemoryRegionCache 内部可以缓存(64)个内存信息。;
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) { //yangyc 参数9：是否全部的线程都使用 PoolThreadCache 技术, 默认true, 表示都使用; 参数10：对齐填充,0值;
        super(preferDirect); //yangyc 将 “是否偏向堆外内存” 的信息交给父类, 父类根据该信息进行路由
        threadCache = new PoolThreadLocalCache(useCacheForAllThreads); //yangyc 类似于 ThreadLocal 对象, 每个线程到 threadCache 内可以获取到一个当前线程自己的 PoolThreadCache 对象
        this.tinyCacheSize = tinyCacheSize;     //yangyc TinyMemoryRegionCache 内部可以缓存(512)个内存信息。
        this.smallCacheSize = smallCacheSize;   //yangyc SmallMemoryRegionCache 内部可以缓存(256)个内存信息。
        this.normalCacheSize = normalCacheSize; //yangyc NormalMemoryRegionCache 内部可以缓存(64)个内存信息。
        chunkSize = validateAndCalculateChunkSize(pageSize, maxOrder); //yangyc 根据 pageSize 和 满二叉树节点深度计算出 chunkSize. 默认情况下: pageSize:8K,maxOrder:11 ==> 16mb

        checkPositiveOrZero(nHeapArena, "nHeapArena");
        checkPositiveOrZero(nDirectArena, "nDirectArena");

        checkPositiveOrZero(directMemoryCacheAlignment, "directMemoryCacheAlignment");
        if (directMemoryCacheAlignment > 0 && !isDirectMemoryCacheAlignmentSupported()) {
            throw new IllegalArgumentException("directMemoryCacheAlignment is not supported");
        }

        if ((directMemoryCacheAlignment & -directMemoryCacheAlignment) != directMemoryCacheAlignment) {
            throw new IllegalArgumentException("directMemoryCacheAlignment: "
                    + directMemoryCacheAlignment + " (expected: power of two)");
        }

        int pageShifts = validateAndCalculatePageShifts(pageSize); //yangyc 当pageSize=8K ==> pageShifts=13. ==> 1左移13位可以得到 pageSize

        if (nHeapArena > 0) { //yangyc 一般条件成立
            heapArenas = newArenaArray(nHeapArena); //yangyc 假设CPU个数8. 这里创建 16 长度的 heapArena 数组
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(heapArenas.length); //yangyc 监控报表相关
            for (int i = 0; i < heapArenas.length; i ++) { //yangyc 循环最终创建16个 heapArena 对象, 并且将这些对象放入到数组内
                PoolArena.HeapArena arena = new PoolArena.HeapArena(this,
                        pageSize, maxOrder, pageShifts, chunkSize,
                        directMemoryCacheAlignment);
                heapArenas[i] = arena;
                metrics.add(arena);
            }
            heapArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            heapArenas = null;
            heapArenaMetrics = Collections.emptyList();
        }

        if (nDirectArena > 0) { //yangyc 一般条件成立
            directArenas = newArenaArray(nDirectArena); //yangyc 假设CPU个数8. 这里创建 16 长度的 directArena 数组
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(directArenas.length); //yangyc 监控报表相关
            for (int i = 0; i < directArenas.length; i ++) { //yangyc 循环最终创建16个 heapArena 对象, 并且将这些对象放入到数组内
                PoolArena.DirectArena arena = new PoolArena.DirectArena( //yangyc 参数1:当前PooledByteBufAllocator对象; 参数2:8K; 参数3:满二叉树深度11; 参数4: 13--1左移13位可以得到pageSize; 参数5:16mb; 参数6:0;
                        this, pageSize, maxOrder, pageShifts, chunkSize, directMemoryCacheAlignment);
                directArenas[i] = arena;
                metrics.add(arena);
            }
            directArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            directArenas = null;
            directArenaMetrics = Collections.emptyList();
        }
        metric = new PooledByteBufAllocatorMetric(this);
    }

    @SuppressWarnings("unchecked")
    private static <T> PoolArena<T>[] newArenaArray(int size) {
        return new PoolArena[size];
    }

    private static int validateAndCalculatePageShifts(int pageSize) {
        if (pageSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: " + MIN_PAGE_SIZE + ")");
        }

        if ((pageSize & pageSize - 1) != 0) { //yangyc 要求pageSize 是 2的n次幂
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: power of 2)");
        }

        // Logarithm base 2. At this point we know that pageSize is a power of two.//yangyc Integer.numberOfLeadingZeros(pageSize):pageSize 前面有多少个0.   32-1-18=13
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(pageSize); //yangyc 即：1左移13位可以得到 pageSize
    }

    private static int validateAndCalculateChunkSize(int pageSize, int maxOrder) {
        if (maxOrder > 14) {
            throw new IllegalArgumentException("maxOrder: " + maxOrder + " (expected: 0-14)");
        }

        // Ensure the resulting chunkSize does not overflow.
        int chunkSize = pageSize;
        for (int i = maxOrder; i > 0; i --) {
            if (chunkSize > MAX_CHUNK_SIZE / 2) {
                throw new IllegalArgumentException(String.format(
                        "pageSize (%d) << maxOrder (%d) must not exceed %d", pageSize, maxOrder, MAX_CHUNK_SIZE));
            }
            chunkSize <<= 1;
        }
        return chunkSize;
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        PoolThreadCache cache = threadCache.get();
        PoolArena<byte[]> heapArena = cache.heapArena;

        final ByteBuf buf;
        if (heapArena != null) {
            buf = heapArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            buf = PlatformDependent.hasUnsafe() ?
                    new UnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        return toLeakAwareBuffer(buf);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) { //yangyc 参数1:业务需求内存大小; 参数2:最大内存大小;
        PoolThreadCache cache = threadCache.get(); //yangyc 每个线程都去获取一个当前线程的 PoolTheadCache 对象。注意: 每个线程都有自己的 PoolThreadCache 对象。并且每个PoolThreadCache对象内部包含两个区域: HeapArena 和 DirectArena
        PoolArena<ByteBuffer> directArena = cache.directArena; //yangyc 获取分配给当前线程使用的 directArena 区域, 后面要在这块区域内申请内存

        final ByteBuf buf;
        if (directArena != null) { //yangyc 条件一般成立
            buf = directArena.allocate(cache, initialCapacity, maxCapacity); //yangyc 参数1:当前线程相关的PoolThreadCache对象; 参数2:业务层需要的内存容量; 参数3:最大内存容量;
        } else {
            buf = PlatformDependent.hasUnsafe() ?
                    UnsafeByteBufUtil.newUnsafeDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }
        //yangyc 该方法会根据检测级别进行追踪 byteBuf 资源对象, 默认检测级别是 simple
        return toLeakAwareBuffer(buf); //yangyc 会抽样一小部分 byteBuf 进行资源追踪，当被追踪的资源发生泄漏后, 资源检测器会打印 error 日志
    }

    /**
     * Default number of heap arenas - System Property: io.netty.allocator.numHeapArenas - default 2 * cores
     */
    public static int defaultNumHeapArena() {
        return DEFAULT_NUM_HEAP_ARENA;
    }

    /**
     * Default number of direct arenas - System Property: io.netty.allocator.numDirectArenas - default 2 * cores
     */
    public static int defaultNumDirectArena() {
        return DEFAULT_NUM_DIRECT_ARENA;
    }

    /**
     * Default buffer page size - System Property: io.netty.allocator.pageSize - default 8192
     */
    public static int defaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * Default maximum order - System Property: io.netty.allocator.maxOrder - default 11
     */
    public static int defaultMaxOrder() {
        return DEFAULT_MAX_ORDER;
    }

    /**
     * Default thread caching behavior - System Property: io.netty.allocator.useCacheForAllThreads - default true
     */
    public static boolean defaultUseCacheForAllThreads() {
        return DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    }

    /**
     * Default prefer direct - System Property: io.netty.noPreferDirect - default false
     */
    public static boolean defaultPreferDirect() {
        return PlatformDependent.directBufferPreferred();
    }

    /**
     * Default tiny cache size - System Property: io.netty.allocator.tinyCacheSize - default 512
     */
    public static int defaultTinyCacheSize() {
        return DEFAULT_TINY_CACHE_SIZE;
    }

    /**
     * Default small cache size - System Property: io.netty.allocator.smallCacheSize - default 256
     */
    public static int defaultSmallCacheSize() {
        return DEFAULT_SMALL_CACHE_SIZE;
    }

    /**
     * Default normal cache size - System Property: io.netty.allocator.normalCacheSize - default 64
     */
    public static int defaultNormalCacheSize() {
        return DEFAULT_NORMAL_CACHE_SIZE;
    }

    /**
     * Return {@code true} if direct memory cache alignment is supported, {@code false} otherwise.
     */
    public static boolean isDirectMemoryCacheAlignmentSupported() {
        return PlatformDependent.hasUnsafe();
    }

    @Override
    public boolean isDirectBufferPooled() {
        return directArenas != null;
    }

    /**
     * Returns {@code true} if the calling {@link Thread} has a {@link ThreadLocal} cache for the allocated
     * buffers.
     */
    @Deprecated
    public boolean hasThreadLocalCache() {
        return threadCache.isSet();
    }

    /**
     * Free all cached buffers for the calling {@link Thread}.
     */
    @Deprecated
    public void freeThreadLocalCache() {
        threadCache.remove();
    }

    final class PoolThreadLocalCache extends FastThreadLocal<PoolThreadCache> {
        private final boolean useCacheForAllThreads; //yangyc true

        PoolThreadLocalCache(boolean useCacheForAllThreads) {
            this.useCacheForAllThreads = useCacheForAllThreads;
        }

        @Override
        protected synchronized PoolThreadCache initialValue() {
            final PoolArena<byte[]> heapArena = leastUsedArena(heapArenas);          //yangyc 获取 arena 数组中, 线程共享数最低的 arena 对象
            final PoolArena<ByteBuffer> directArena = leastUsedArena(directArenas);  //yangyc 获取 arena 数组中, 线程共享数最低的 arena 对象

            final Thread current = Thread.currentThread();
            if (useCacheForAllThreads || current instanceof FastThreadLocalThread) {//yangyc 条件1一般成立； 条件2: netty线程一般都是 NioEventLoop线程, NioEventLoop 线程由 DefaultThreadFactory 创建, 它创建的线程都是 FastThreadLocalThread
                final PoolThreadCache cache = new PoolThreadCache( //yangyc 创建归属当前线程的 cache 对象, 参数1:heapArena; 参数2:directArena; 参数3:512; 参数4:256; 参数5:64;
                        heapArena, directArena, tinyCacheSize, smallCacheSize, normalCacheSize,
                        DEFAULT_MAX_CACHED_BUFFER_CAPACITY, DEFAULT_CACHE_TRIM_INTERVAL); //yangyc  参数6:32K; 参数7:8192 一个阈值;

                if (DEFAULT_CACHE_TRIM_INTERVAL_MILLIS > 0) {
                    final EventExecutor executor = ThreadExecutorMap.currentExecutor();
                    if (executor != null) {
                        executor.scheduleAtFixedRate(trimTask, DEFAULT_CACHE_TRIM_INTERVAL_MILLIS,
                                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }
                return cache;
            }
            // No caching so just use 0 as sizes.
            return new PoolThreadCache(heapArena, directArena, 0, 0, 0, 0, 0);
        }

        @Override
        protected void onRemoval(PoolThreadCache threadCache) {
            threadCache.free(false);
        }

        private <T> PoolArena<T> leastUsedArena(PoolArena<T>[] arenas) { //yangyc 返回 arena 数组内, 线程共享数最低的 arena 对象
            if (arenas == null || arenas.length == 0) {
                return null;
            }

            PoolArena<T> minArena = arenas[0];
            for (int i = 1; i < arenas.length; i++) {
                PoolArena<T> arena = arenas[i];
                if (arena.numThreadCaches.get() < minArena.numThreadCaches.get()) {
                    minArena = arena;
                }
            }

            return minArena;
        }
    }

    @Override
    public PooledByteBufAllocatorMetric metric() {
        return metric;
    }

    /**
     * Return the number of heap arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numHeapArenas()}.
     */
    @Deprecated
    public int numHeapArenas() {
        return heapArenaMetrics.size();
    }

    /**
     * Return the number of direct arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numDirectArenas()}.
     */
    @Deprecated
    public int numDirectArenas() {
        return directArenaMetrics.size();
    }

    /**
     * Return a {@link List} of all heap {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#heapArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> heapArenas() {
        return heapArenaMetrics;
    }

    /**
     * Return a {@link List} of all direct {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#directArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> directArenas() {
        return directArenaMetrics;
    }

    /**
     * Return the number of thread local caches used by this {@link PooledByteBufAllocator}.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numThreadLocalCaches()}.
     */
    @Deprecated
    public int numThreadLocalCaches() {
        PoolArena<?>[] arenas = heapArenas != null ? heapArenas : directArenas;
        if (arenas == null) {
            return 0;
        }

        int total = 0;
        for (PoolArena<?> arena : arenas) {
            total += arena.numThreadCaches.get();
        }

        return total;
    }

    /**
     * Return the size of the tiny cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#tinyCacheSize()}.
     */
    @Deprecated
    public int tinyCacheSize() {
        return tinyCacheSize;
    }

    /**
     * Return the size of the small cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#smallCacheSize()}.
     */
    @Deprecated
    public int smallCacheSize() {
        return smallCacheSize;
    }

    /**
     * Return the size of the normal cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#normalCacheSize()}.
     */
    @Deprecated
    public int normalCacheSize() {
        return normalCacheSize;
    }

    /**
     * Return the chunk size for an arena.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#chunkSize()}.
     */
    @Deprecated
    public final int chunkSize() {
        return chunkSize;
    }

    final long usedHeapMemory() {
        return usedMemory(heapArenas);
    }

    final long usedDirectMemory() {
        return usedMemory(directArenas);
    }

    private static long usedMemory(PoolArena<?>[] arenas) {
        if (arenas == null) {
            return -1;
        }
        long used = 0;
        for (PoolArena<?> arena : arenas) {
            used += arena.numActiveBytes();
            if (used < 0) {
                return Long.MAX_VALUE;
            }
        }
        return used;
    }

    final PoolThreadCache threadCache() {
        PoolThreadCache cache =  threadCache.get();
        assert cache != null;
        return cache;
    }

    /**
     * Trim thread local cache for the current {@link Thread}, which will give back any cached memory that was not
     * allocated frequently since the last trim operation.
     *
     * Returns {@code true} if a cache for the current {@link Thread} exists and so was trimmed, false otherwise.
     */
    public boolean trimCurrentThreadCache() {
        PoolThreadCache cache = threadCache.getIfExists();
        if (cache != null) {
            cache.trim();
            return true;
        }
        return false;
    }

    /**
     * Returns the status of the allocator (which contains all metrics) as string. Be aware this may be expensive
     * and so should not called too frequently.
     */
    public String dumpStats() {
        int heapArenasLen = heapArenas == null ? 0 : heapArenas.length;
        StringBuilder buf = new StringBuilder(512)
                .append(heapArenasLen)
                .append(" heap arena(s):")
                .append(StringUtil.NEWLINE);
        if (heapArenasLen > 0) {
            for (PoolArena<byte[]> a: heapArenas) {
                buf.append(a);
            }
        }

        int directArenasLen = directArenas == null ? 0 : directArenas.length;

        buf.append(directArenasLen)
           .append(" direct arena(s):")
           .append(StringUtil.NEWLINE);
        if (directArenasLen > 0) {
            for (PoolArena<ByteBuffer> a: directArenas) {
                buf.append(a);
            }
        }

        return buf.toString();
    }
}
