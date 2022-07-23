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

package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractBootstrap} is a helper class that makes it easy to bootstrap a {@link Channel}. It support
 * method-chaining to provide an easy way to configure the {@link AbstractBootstrap}.
 *
 * <p>When not used in a {@link ServerBootstrap} context, the {@link #bind()} methods are useful for connectionless
 * transports such as datagram (UDP).</p>
 * 两个泛型:
 * B ：继承 AbstractBootstrap 类，用于表示自身的类型。
 * C ：继承 Channel 类，表示表示创建的 Channel 类型。
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    volatile EventLoopGroup group;//yangyc EventLoopGroup 对象
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory;//yangyc Channel工厂类，用于创建channel
    private volatile SocketAddress localAddress;//yangyc 本地地址

    // The order in which ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();//yangyc 可选项集合
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();//yangyc 属性集合
    private volatile ChannelHandler handler;//yangyc handle处理器

    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        synchronized (bootstrap.options) { //yangyc 加 synchronized 原因：其他线程可能调用 option(hannelOption<T> option, T value)
            options.putAll(bootstrap.options);
        }
        attrs.putAll(bootstrap.attrs);
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created
     * {@link Channel}
     */
    public B group(EventLoopGroup group) { //yangyc 设置EventLoopGroup，并返回自己----链式调用
        ObjectUtil.checkNotNull(group, "group");
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        this.group = group;
        return self();
    }

    @SuppressWarnings("unchecked")
    private B self() { //yangyc return自己----用于链式调用
        return (B) this;
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     */
    public B channel(Class<? extends C> channelClass) {  //yangyc 设置要被实例化的 Channel 的类
        return channelFactory(new ReflectiveChannelFactory<C>( //yangyc channel的反射工厂类, class -> constructor -> newInstance
                ObjectUtil.checkNotNull(channelClass, "channelClass") //yangyc 传个 class，以便于拿到channel类构造方法并缓存，以后就可以基于这个构造方法newChannel
        ));
    }

    /**
     * @deprecated Use {@link #channelFactory(io.netty.channel.ChannelFactory)} instead.
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }

        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * {@link io.netty.channel.ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} to
     * simplify your code.
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     */
    public B localAddress(SocketAddress localAddress) { //yangyc 重载----设置创建 Channel 的本地地址
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) { //yangyc 重载----设置创建 Channel 的本地地址
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) { //yangyc 重载----设置创建 Channel 的本地地址
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) { //yangyc 重载----设置创建 Channel 的本地地址
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     */
    public <T> B option(ChannelOption<T> option, T value) { //yangyc 设置创建 Channel 的可选项
        ObjectUtil.checkNotNull(option, "option");
        synchronized (options) {
            if (value == null) {
                options.remove(option); //yangyc 空，意味着移除
            } else {
                options.put(option, value); //yangyc 非空，进行修改
            }
        }
        return self();
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> B attr(AttributeKey<T> key, T value) { //yangyc 设置创建 Channel 的属性
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);  //yangyc 空，意味着移除
        } else {
            attrs.put(key, value); //yangyc 非空，进行修改
        }
        return self();
    }

    /**
     * Validate all the parameters. Sub-classes may override this, but should
     * call the super method in that case.
     */
    public B validate() { //yangyc 校验配置是否正确----#bind(...) 中，绑定本地地址时，会调用该方法进行校验
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone(); //yangyc 克隆一个 AbstractBootstrap 对象

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind() { //yangyc-main 绑定本地地址 + 包括端口
        validate(); //yangyc  校验服务启动需要的必要参数
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress); //yangyc-main 绑定本地地址 + 包括端口
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(int inetPort) { //yangyc 参数: 端口号
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    //yangyc 启动服务器绑定端口(异步)
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        validate(); //yangyc 校验配置是否正确
        return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));
    }

    private ChannelFuture doBind(final SocketAddress localAddress) { //yangyc-main 绑定网络监听端口
        final ChannelFuture regFuture = initAndRegister(); //yangyc-main 初始化并注册一个 Channel 对象，因为注册是异步的过程，所以返回一个 ChannelFuture 对象， regFuture 是注册相关的promise对象，他关联的异步任务是 register0()， register0()被加在channel相关的eventloop工作队列
        final Channel channel = regFuture.channel(); //yangyc 拿到 NioServerSocketChannel 对象
        if (regFuture.cause() != null) { //yangyc 若发生异常，直接进行返回
            return regFuture;
        }

        if (regFuture.isDone()) { //yangyc 因为注册（register0()在工作队列中）是异步的过程，有可能已完成，有可能未完成------未已完成，如果register0()执行完done是true
            // At this point we know that the registration was complete and successful.
            ChannelPromise promise = channel.newPromise();
            doBind0(regFuture, channel, localAddress, promise); //yangyc-main 绑定 Channel 的端口，并注册 Channel 到 SelectionKey 中
            return promise;
        } else { //yangyc 因为注册是异步的过程，有可能已完成，有可能未完成------register0未完成
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            regFuture.addListener(new ChannelFutureListener() { //yangyc 给register0相关的promise对象添加监听器，在register0注册完成后，进行回调执行 #doBind0(...)
                @Override
                public void operationComplete(ChannelFuture future) throws Exception { //yangyc 这个方法不是主线程执行的，主线程只是添加监听器，是回调线程eventloop调用的
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.registered();

                        doBind0(regFuture, channel, localAddress, promise); //yangyc 在监听器中绑定端口
                    }
                }
            });
            return promise; //yangyc 主线程返回一个与bind操作相关的promise对象
        }
    }

    final ChannelFuture initAndRegister() { //yangyc-main 初始化并注册一个 Channel 对象，并返回一个 ChannelFuture 对象
        Channel channel = null;
        try {
            //yangyc NioServerSocketChannel 构造方法：1.获得JDK层面的ServerSocketChannel，2.保存感兴趣事件-Accept, 3.设置非阻塞，4.创建unsafe对象（NioMessageUnsafe）,4.创建pipeline
            channel = channelFactory.newChannel(); //yangyc-main 创建 Channel 对象 --- [ServerBoostrap -> ReflectiveChannelFactory -> NioServerSocketChannel()] --- channelFactory的创建过程在channel(NioServerSocketChannel.class)
            //yangyc 给当前服务端 Channel 的pipeline添加一个ChannelInitializer（hanlder压缩包）
            init(channel); //yangyc-main 初始化 Channel 配置 --- 具体实现在子类 ServerBootStrap 和 Boostrap 里
        } catch (Throwable t) {
            if (channel != null) {
                // channel can be null if newChannel crashed (eg SocketException("too many open files"))
                channel.unsafe().closeForcibly(); //yangyc 如果已创建 Channel 对象，则强制关闭 Channel
                // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t); //yangyc 创建 Channel 对象失败, 则创建一个 FailedChannel 对象，并设置到 DefaultChannelPromise 中且可以返回
        }
        //yangyc config()=>ServerBootstrapConfig(ServerBootstrap);   group()=>boos组（NioEventLoopGroup）;    register(channel)=>MultithreadEventLoopGroup#register()
        ChannelFuture regFuture = config().group().register(channel);  //yangyc-main 从 boosGroup 里拿一个线程来处理 Channel，并将其注册到自己的 Selector, [MultithreadEventLoopGroup#register()]
        if (regFuture.cause() != null) { //yangyc regFuture 是注册相关的promise对象
            if (channel.isRegistered()) {
                channel.close();
            } else {
                channel.unsafe().closeForcibly(); //yangyc 强制关闭 Channel
            }
        }

        // If we are here and the promise is not failed, it's one of the following cases:
        // 1) If we attempted registration from the event loop, the registration has been completed at this point.
        //    i.e. It's safe to attempt bind() or connect() now because the channel has been registered.
        // 2) If we attempted registration from the other thread, the registration request has been successfully
        //    added to the event loop's task queue for later execution.
        //    i.e. It's safe to attempt bind() or connect() now:
        //         because bind() or connect() will be executed *after* the scheduled registration task is executed
        //         because register(), bind(), and connect() are all bound to the same thread.

        return regFuture;
    }

    abstract void init(Channel channel) throws Exception;

    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) { //yangyc 执行 Channel 的端口绑定逻辑

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() { //yangyc-main 提交异步任务到eventloop工作队列: 【异步任务3--添加】--- xx#xx()时执行
                if (regFuture.isSuccess()) { //yangyc 注册成功，绑定端口
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE); //yangyc-main 异步任务3执行 绑定网络端口
                } else { //yangyc 注册失败，回调通知 promise 异常
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     */
    public B handler(ChannelHandler handler) { //yangyc 设置 Channel 的处理器
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return self();
    }

    /**
     * Returns the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /**
     * Returns the {@link AbstractBootstrapConfig} object that can be used to obtain the current config
     * of the bootstrap.
     */
    public abstract AbstractBootstrapConfig<B, C> config(); //yangyc 返回当前 AbstractBootstrap 的配置对象

    final Map.Entry<ChannelOption<?>, Object>[] newOptionsArray() {
        return newOptionsArray(options);
    }

    static Map.Entry<ChannelOption<?>, Object>[] newOptionsArray(Map<ChannelOption<?>, Object> options) {
        synchronized (options) {
            return new LinkedHashMap<ChannelOption<?>, Object>(options).entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
    }

    final Map.Entry<AttributeKey<?>, Object>[] newAttributesArray() {
        return newAttributesArray(attrs0());
    }

    static Map.Entry<AttributeKey<?>, Object>[] newAttributesArray(Map<AttributeKey<?>, Object> attributes) {
        return attributes.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);
    }

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    final SocketAddress localAddress() { //yangyc 重载----设置创建 Channel 的本地地址
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() { //yangyc 返回 Channel 的处理器
        return handler;
    }

    final Map<ChannelOption<?>, Object> options() {
        synchronized (options) {
            return copiedMap(options);
        }
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e: attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    static void setChannelOptions( //yangyc 设置传入的 Channel 的多个可选项
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setChannelOption( //yangyc 设置传入的 Channel 的一个可选项
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return super.executor();
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}
