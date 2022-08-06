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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    private final int maxLength; //yangyc 业务层制定的协议 frame 最大长度， 如果客户端发来的业务数据包超过此长度，解码器需要抛异常
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    private final boolean failFast; //yangyc 释放快速失败，默认false
    private final boolean stripDelimiter; //yangyc 是否跳过 ”分隔符“ 字节，默认false

    /** True if we're discarding input because we're already over maxLength.  */
    private boolean discarding; //yangyc 是否为丢弃模式，（当前数据包超过了maxLength，还未出现分隔符时，是否丢弃）
    private int discardedBytes; //yangyc 丢弃模式下，记录已丢弃的数据量

    /** Last scan position. */
    private int offset; //yangyc 上一次堆积区的扫描位点，只有堆积区是半包数据时，才会使用到

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer); //yangyc 从堆积区 byteBuf 查找换行符的位置，返回-1，表示从当前堆积区未查找到换行符，返回>=0,表示换行符在堆积区中的位置
        if (!discarding) { //yangyc 条件成立: 说明当前 decoder 工作模式是正常模式（非丢弃模式）
            if (eol >= 0) {  //yangyc 条件成立: 说明在堆积区 ByteBuf 查找到了换行符
                final ByteBuf frame; //yangyc 最终指向业务层的数据帧
                final int length = eol - buffer.readerIndex(); //yangyc 计算出当前业务层数据包长度
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1; //yangyc 表示换行符长度，如果换行符长度是 "\r\n" 则2, 否则 "\n"是 1

                if (length > maxLength) { //yangyc 说明本地数据包长度大于业务层制定的最大包长度， 需要跳过并且抛异常
                    buffer.readerIndex(eol + delimLength); //yangyc 将整个数据包跳过
                    fail(ctx, length);
                    return null; //yangyc 返回null, 未能从堆积区解析出数满足业务层协议的数据包
                }
                //yangyc 正常逻辑走到这里
                if (stripDelimiter) {
                    frame = buffer.readRetainedSlice(length);
                    buffer.skipBytes(delimLength);
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame; //yangyc 返回正常切片出来的数据包
            } else { //yangyc 执行到这里，说明堆积区内未查找到换行符，堆积区现在是半包数据
                final int length = buffer.readableBytes(); //yangyc 获取堆积区可用数据量长度
                if (length > maxLength) { //yangyc 条件成立: 说明堆积区内的数据量 > 最大帧长度，需要开启丢弃模式
                    discardedBytes = length;
                    buffer.readerIndex(buffer.writerIndex());
                    discarding = true; //yangyc 开启丢弃模式
                    offset = 0;
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null; //yangyc 返回null, 未能从堆积区解析出数据
            }
        } else { //yangyc 执行到这里，说明当前 decoder 处于丢弃模式
            if (eol >= 0) { //yangyc 条件成立:说明当前堆积区已经查找到换行符了，需要丢弃半包数据，并且设置 decoder 为正常模式
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                buffer.readerIndex(eol + delimLength);
                discardedBytes = 0;
                discarding = false;
                if (!failFast) {
                    fail(ctx, length);
                }
            } else { //yangyc 执行到这里，说明仍然未查找到换行符，需要继续丢弃模式，并保持丢弃模式
                discardedBytes += buffer.readableBytes();
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes(); //yangyc 获取堆积区的总长度
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF); //yangyc 返回换行符在堆积区的位置
        if (i >= 0) { //yangyc 条件成立:说明在堆积区内查找到了换行符了
            offset = 0;
            if (i > 0 && buffer.getByte(i - 1) == '\r') { //yangyc 条件成立:说明在堆积区内查找到的换行符是 "\r\n"
                i--; //yangyc i-- 要返回\r的位置，而不是\n
            }
        } else { //yangyc 说明在堆积区内未查找到换行符，需要设置offset为totalLength，因为当前堆积区内是一个半包业务数据，下次堆积区再次累积新数据之后，再调用当前decode,需要从offset开始检查换行符，提高效率
            offset = totalLength;
        }
        return i;
    }
}
