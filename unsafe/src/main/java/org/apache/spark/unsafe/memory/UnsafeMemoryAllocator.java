/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.unsafe.memory;

import org.apache.spark.unsafe.Platform;

/**
 * A simple {@link MemoryAllocator} that uses {@code Unsafe} to allocate off-heap memory.
 * 一个简单的{@link MemoryAllocator}使用{@code Unsafe}来分配堆内存
 */
public class UnsafeMemoryAllocator implements MemoryAllocator {

  @Override
  public MemoryBlock allocate(long size) throws OutOfMemoryError {
    if (size % 8 != 0) {
      throw new IllegalArgumentException("Size " + size + " was not a multiple of 8");
    }
    long address = Platform.allocateMemory(size);
    return new MemoryBlock(null, address, size);
  }

  @Override
  public void free(MemoryBlock memory) {
    assert (memory.obj == null) :
      "baseObject not null; are you trying to use the off-heap allocator to free on-heap memory?";
    Platform.freeMemory(memory.offset);
  }
}
