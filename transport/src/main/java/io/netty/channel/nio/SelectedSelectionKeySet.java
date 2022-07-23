/*
 * Copyright 2013 The Netty Project
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
package io.netty.channel.nio;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> { //yangyc 已 select 的 NIO SelectionKey 集合

    SelectionKey[] keys; //yangyc SelectionKey 数组
    int size; //yangyc 数组可读大小

    SelectedSelectionKeySet() {
        keys = new SelectionKey[1024]; //yangyc 默认 1024 大小
    }

    @Override
    public boolean add(SelectionKey o) {
        if (o == null) {
            return false;
        }

        keys[size++] = o; //yangyc 添加新 select 到就绪事件的 SelectionKey 到 keys 中
        if (size == keys.length) { //yangyc 超过数组大小上限 --- 扩容
            increaseCapacity();  //yangyc 超过数组大小上限，进行扩容
        }

        return true;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<SelectionKey> iterator() {
        return new Iterator<SelectionKey>() {
            private int idx;

            @Override
            public boolean hasNext() {
                return idx < size;
            }

            @Override
            public SelectionKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return keys[idx++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    void reset() {
        reset(0);
    }

    void reset(int start) { //yangyc 每次读取使用完数据，调用该方法，进行重置
        Arrays.fill(keys, start, size, null); //yangyc 重置数组内容为空
        size = 0; //yangyc 重置可读大小为 0
    }

    private void increaseCapacity() {
        SelectionKey[] newKeys = new SelectionKey[keys.length << 1]; //yangyc 两倍扩容
        System.arraycopy(keys, 0, newKeys, 0, size); //yangyc 复制老数组到新数组
        keys = newKeys; //yangyc 赋值给老数组
    }
}
