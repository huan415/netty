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
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>(); //yangyc 子 Channel 的可选项集合
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>(); //yangyc 子 Channel 的属性集合
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this); //yangyc 启动类配置对象
    private volatile EventLoopGroup childGroup; //yangyc 子 Channel 的 EventLoopGroup 对象
    private volatile ChannelHandler childHandler; //yangyc 子 Channel 的处理器

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) { //yangyc 设置 EventLoopGroup 到 group、childGroup
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) { //yangyc 设置 EventLoopGroup 到 group、childGroup
        super.group(parentGroup);
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) { //yangyc 设置子 Channel 的可选项
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption); //yangyc 空，意味着移除
            } else {
                childOptions.put(childOption, value); //yangyc 非空，进行修改
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) { //yangyc 设置子 Channel 的属性
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey); //yangyc 空，意味着移除
        } else {
            childAttrs.put(childKey, value); //yangyc 非空，进行修改
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) { //yangyc 设置子 Channel 的处理器
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    @Override
    void init(Channel channel) { //yangyc-main 初始化 Channel 配置
        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());

        ChannelPipeline p = channel.pipeline(); //yangyc 获取服务端的 pipeline 管道
        //yangyc 记录当前的属性
        final EventLoopGroup currentChildGroup = childGroup;  //yangyc 其实就是 workerGroup
        final ChannelHandler currentChildHandler = childHandler; //yangyc 其实就是我们写服务端，很长的那段代码：.childHandler(new ChannelInitializer<SocketChannel>() {@Override public void initChannel(SocketChannel ch)})
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions); //yangyc 客户端 socket 选项
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs); //yangyc 所有的channel都实现了AttributeMap接口，在启动类配置一些自定义数据，所有的channel实例中都有这些数据
        //yangyc ChannelInitializer 本身不是Handler,只是通过适配器实现了Handler接口，存在的意思：为了延迟初始化pipleLine----当Pipeline的channel被激活以后，真正添加到handler逻辑才执行
            p.addLast(new ChannelInitializer<Channel>() { //yangyc-main 将 ChannelInitializer 加入 pipeline 后，会调用下面的 initChannel()
            @Override //yangyc-main 理解：pipeline:head->ChannelInitializer->tail。此时 ChannelInitializer 本身不是handle,类似于zip, 当NioServerSocketChannel激活之后，调用initChannel()==将ChannelInitializer解压后加入pipeline，并且将自己移除
            public void initChannel(final Channel ch) { //yangyc-main 添加 ChannelInitializer 对象到 pipeline 中，用于后续初始化 ChannelHandler 到 pipeline 中
                final ChannelPipeline pipeline = ch.pipeline();
                ChannelHandler handler = config.handler(); //yangyc 添加配置的 ChannelHandler 到 pipeline 中。实际就是ServerBootstrap.handler()中添加的 handler
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() { //yangyc-main 提交异步任务到eventloop工作队列:任务2； 添加 ServerBootstrapAcceptor 到 pipeline 中。
                        //yangyc 参数1：服务端channel, 参数2：worker线程组，参数3：模板代码配置的 childrenHandler(ChannelInitializer)用于初始化客户端pipeline，参数4：客户端选项，参数5：客户端参数
                        pipeline.addLast(new ServerBootstrapAcceptor( //yangyc （异步任务2执行） pipeline添加 ServerBootstrapAcceptor 处理器。 【异步任务2--添加】--- 在AbstractChannel#register0()时执行---pipeline.invokeHandlerAddedIfNeeded()
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() { //yangyc 校验配置是否正确
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter { //yangyc-main 服务器接收器( acceptor )，负责将接受的客户端的 NioSocketChannel 注册到 EventLoop 中

        private final EventLoopGroup childGroup;
        private final ChannelHandler childHandler;
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor( //yangyc 参数1：服务端channel, 参数2：worker线程组，参数3：模板代码配置的 childrenHandler(ChannelInitializer)用于初始化客户端pipeline，参数4：客户端选项，参数5：客户端参数
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")  //yangyc 参数1：包装当前handler的ctx, 参数2：NioSocketChannel 实例
        public void channelRead(ChannelHandlerContext ctx, Object msg) {  //yangyc-main 将接受的客户端的 NioSocketChannel 注册到 EventLoop 中 [ServerBootstrapAcceptor]
            final Channel child = (Channel) msg;  //yangyc 接受的客户端的 NioSocketChannel 对象
            //yangyc child.pipeline()=>获取客户端 Channel 的 pipeline 对象
            child.pipeline().addLast(childHandler); //yangyc-main 添加 NioSocketChannel 的处理器, childHandler 就是我们自己写的 ChannelInitializer

            setChannelOptions(child, childOptions, logger); //yangyc 设置 NioSocketChannel 的配置项
            setAttributes(child, childAttrs); //yangyc 设置 NioSocketChannel 的属性

            try {
                //yangyc register做的事情：1. work组内分配一个NioEventLoop给当前NioSocketChannel使用(NioEventLoop是多个Channel共享的) 2.完成底层ChannelSocket注册到底层多路复用器，3.向NioSocketChannel pipeline发起Active事件，由head响应，head通过unsafe修改当前channel 的selectionKey感兴趣事件：read
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception { //yangyc-main childGroup:worker 线程组; register() 客户端注册逻辑. 注册客户端的 NioSocketChannel 到 work EventLoop 中。
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause()); //yangyc 注册失败，关闭客户端的 NioSocketChannel
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);  //yangyc 发生异常，强制关闭客户端的 NioSocketChannel
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception { //yangyc 当捕获到异常时，暂停 1 秒，不再接受新的客户端连接；而后，再恢复接受新的客户端连接
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false); //yangyc 关闭接受新的客户端连接 --- [DefaultChannelConfig]
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);   //yangyc 发起 1 秒的延迟任务，恢复重启开启接受新的客户端连接
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);  //yangyc 继续传播 exceptionCaught 给下一个节点
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() { //yangyc 克隆 ServerBootstrap 对象
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
