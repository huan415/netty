/*
 * Copyright 2019 The Netty Project
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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.PlatformDependent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.WeakHashMap;

final class ChannelHandlerMask {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelHandlerMask.class);

    // Using to mask which methods must be called for a ChannelHandler.
    static final int MASK_EXCEPTION_CAUGHT = 1;                  //yangyc 0b 0000 0000 0000 0000 0000 0000 0000 0001
    static final int MASK_CHANNEL_REGISTERED = 1 << 1;           //yangyc 0b 0000 0000 0000 0000 0000 0000 0000 0010
    static final int MASK_CHANNEL_UNREGISTERED = 1 << 2;         //yangyc 0b 0000 0000 0000 0000 0000 0000 0000 0100
    static final int MASK_CHANNEL_ACTIVE = 1 << 3;               //yangyc 0b 0000 0000 0000 0000 0000 0000 0000 1000
    static final int MASK_CHANNEL_INACTIVE = 1 << 4;             //yangyc 0b 0000 0000 0000 0000 0000 0000 0001 0000
    static final int MASK_CHANNEL_READ = 1 << 5;                 //yangyc 0b 0000 0000 0000 0000 0000 0000 0010 0000
    static final int MASK_CHANNEL_READ_COMPLETE = 1 << 6;        //yangyc 0b 0000 0000 0000 0000 0000 0000 0100 0000
    static final int MASK_USER_EVENT_TRIGGERED = 1 << 7;         //yangyc 0b 0000 0000 0000 0000 0000 0000 1000 0000
    static final int MASK_CHANNEL_WRITABILITY_CHANGED = 1 << 8;  //yangyc 0b 0000 0000 0000 0000 0000 0001 0000 0000
    static final int MASK_BIND = 1 << 9;                         //yangyc 0b 0000 0000 0000 0000 0000 0010 0000 0000
    static final int MASK_CONNECT = 1 << 10;                     //yangyc 0b 0000 0000 0000 0000 0000 0100 0000 0000
    static final int MASK_DISCONNECT = 1 << 11;                  //yangyc 0b 0000 0000 0000 0000 0000 1000 0000 0000
    static final int MASK_CLOSE = 1 << 12;                       //yangyc 0b 0000 0000 0000 0000 0001 0000 0000 0000
    static final int MASK_DEREGISTER = 1 << 13;                  //yangyc 0b 0000 0000 0000 0000 0010 0000 0000 0000
    static final int MASK_READ = 1 << 14;                        //yangyc 0b 0000 0000 0000 0000 0100 0000 0000 0000
    static final int MASK_WRITE = 1 << 15;                       //yangyc 0b 0000 0000 0000 0000 1000 0000 0000 0000
    static final int MASK_FLUSH = 1 << 16;                       //yangyc 0b 0000 0000 0000 0001 0000 0000 0000 0000

    static final int MASK_ONLY_INBOUND =  MASK_CHANNEL_REGISTERED |   //yangyc 计算出一个入栈事件的掩码  0b 0000 0000 0000 0000 0000 0001 1111 1110
            MASK_CHANNEL_UNREGISTERED | MASK_CHANNEL_ACTIVE | MASK_CHANNEL_INACTIVE | MASK_CHANNEL_READ |
            MASK_CHANNEL_READ_COMPLETE | MASK_USER_EVENT_TRIGGERED | MASK_CHANNEL_WRITABILITY_CHANGED;
    private static final int MASK_ALL_INBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_INBOUND;    //yangyc 计算出一个入栈事件的掩码（包含MASK_EXCEPTION_CAUGHT） 0b 0000 0000 0000 0000 0000 0001 1111 1111
    static final int MASK_ONLY_OUTBOUND =  MASK_BIND | MASK_CONNECT | MASK_DISCONNECT |       //yangyc 计算出一个出栈事件的掩码  0b 0000 0000 0000 0001 1111 1110 0000 0000
            MASK_CLOSE | MASK_DEREGISTER | MASK_READ | MASK_WRITE | MASK_FLUSH;
    private static final int MASK_ALL_OUTBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_OUTBOUND;  //yangyc 计算出一个出栈事件的掩码（包含MASK_EXCEPTION_CAUGHT）  0b 0000 0000 0000 0001 1111 1110 0000 0001

    private static final FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>> MASKS =
            new FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>>() {
                @Override
                protected Map<Class<? extends ChannelHandler>, Integer> initialValue() {
                    return new WeakHashMap<Class<? extends ChannelHandler>, Integer>(32);
                }
            };

    /**
     * Return the {@code executionMask}.
     */
    static int mask(Class<? extends ChannelHandler> clazz) {  //yangyc 参数：hanlder 真实业务处理器的 class
        // Try to obtain the mask from the cache first. If this fails calculate it and put it in the cache for fast
        // lookup in the future.
        Map<Class<? extends ChannelHandler>, Integer> cache = MASKS.get();
        Integer mask = cache.get(clazz);
        if (mask == null) {
            mask = mask0(clazz);
            cache.put(clazz, mask);
        }
        return mask;
    }

    /**
     * Calculate the {@code executionMask}. //yangyc 返回值：二进制中，对应下标的位，代表指定的方法，位为1：说明指定的方法在 handlerType 类型中进行了实现； 位为0：说明指定的方法在 handlerType 类型中没有进行实现，
     */
    private static int mask0(Class<? extends ChannelHandler> handlerType) {  //yangyc 参数：hanlder 真实业务处理器的 class
        int mask = MASK_EXCEPTION_CAUGHT;                     //yangyc 0b 0000 0000 0000 0000 0000 0000 0000 0001
        try {
            if (ChannelInboundHandler.class.isAssignableFrom(handlerType)) {  //yangyc 条件成立：说明 handlerType 类型属于 ChannelInboundHandler 子类
                mask |= MASK_ALL_INBOUND;                     //yangyc  0b 0000 0000 0000 0000 0000 0001 1111 1111
                //yangyc isSkippable() 方法返回 hanlderType 这个 class 有没有重新指定的方法，重新指定之后，指定方法上的@Skip注解就有了
                if (isSkippable(handlerType, "channelRegistered", ChannelHandlerContext.class)) { //yangyc 参数1：hanlder 真实业务处理器的 class, 参数2：检查的方法名，参数3：ChannelHanlderContext.class
                    mask &= ~MASK_CHANNEL_REGISTERED; //yangyc 进入这个方法，说明当前 handlerType 没有重新指定方法. 把当前方法对应的位设置成0
                }
                if (isSkippable(handlerType, "channelUnregistered", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_UNREGISTERED;
                }
                if (isSkippable(handlerType, "channelActive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_ACTIVE;
                }
                if (isSkippable(handlerType, "channelInactive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_INACTIVE;
                }
                if (isSkippable(handlerType, "channelRead", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_CHANNEL_READ;
                }
                if (isSkippable(handlerType, "channelReadComplete", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_READ_COMPLETE;
                }
                if (isSkippable(handlerType, "channelWritabilityChanged", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_WRITABILITY_CHANGED;
                }
                if (isSkippable(handlerType, "userEventTriggered", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_USER_EVENT_TRIGGERED;
                }
            }

            if (ChannelOutboundHandler.class.isAssignableFrom(handlerType)) {
                mask |= MASK_ALL_OUTBOUND;

                if (isSkippable(handlerType, "bind", ChannelHandlerContext.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_BIND;
                }
                if (isSkippable(handlerType, "connect", ChannelHandlerContext.class, SocketAddress.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_CONNECT;
                }
                if (isSkippable(handlerType, "disconnect", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DISCONNECT;
                }
                if (isSkippable(handlerType, "close", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_CLOSE;
                }
                if (isSkippable(handlerType, "deregister", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DEREGISTER;
                }
                if (isSkippable(handlerType, "read", ChannelHandlerContext.class)) {
                    mask &= ~MASK_READ;
                }
                if (isSkippable(handlerType, "write", ChannelHandlerContext.class,
                        Object.class, ChannelPromise.class)) {
                    mask &= ~MASK_WRITE;
                }
                if (isSkippable(handlerType, "flush", ChannelHandlerContext.class)) {
                    mask &= ~MASK_FLUSH;
                }
            }

            if (isSkippable(handlerType, "exceptionCaught", ChannelHandlerContext.class, Throwable.class)) {
                mask &= ~MASK_EXCEPTION_CAUGHT;
            }
        } catch (Exception e) {
            // Should never reach here.
            PlatformDependent.throwException(e);
        }

        return mask;
    }

    @SuppressWarnings("rawtypes")
    private static boolean isSkippable(  //yangyc 参数1：hanlder 真实业务处理器的 class, 参数2：检查的方法名，参数3：ChannelHanlderContext.class
            final Class<?> handlerType, final String methodName, final Class<?>... paramTypes) throws Exception {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                Method m;
                try {
                    m = handlerType.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                            "Class {} missing method {}, assume we can not skip execution", handlerType, methodName, e);
                    }
                    return false;
                }
                return m.isAnnotationPresent(Skip.class);
            }
        });
    }

    private ChannelHandlerMask() { }

    /**
     * Indicates that the annotated event handler method in {@link ChannelHandler} will not be invoked by
     * {@link ChannelPipeline} and so <strong>MUST</strong> only be used when the {@link ChannelHandler}
     * method does nothing except forward to the next {@link ChannelHandler} in the pipeline.
     * <p>
     * Note that this annotation is not {@linkplain Inherited inherited}. If a user overrides a method annotated with
     * {@link Skip}, it will not be skipped anymore. Similarly, the user can override a method not annotated with
     * {@link Skip} and simply pass the event through to the next handler, which reverses the behavior of the
     * supertype.
     * </p>
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Skip {
        // no value
    }
}
