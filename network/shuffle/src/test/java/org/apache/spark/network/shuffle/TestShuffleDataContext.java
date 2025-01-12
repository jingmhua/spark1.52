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

package org.apache.spark.network.shuffle;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.google.common.io.Files;

import org.apache.spark.network.shuffle.protocol.ExecutorShuffleInfo;

/**
 * Manages some sort- and hash-based shuffle data, including the creation
 * 管理一些排序和基于散列的Shuffle数据,包括可以读取的目录的创建和清除
 * and cleanup of directories that can be read by the {@link ExternalShuffleBlockResolver}.
 */
public class TestShuffleDataContext {
  public final String[] localDirs;
  public final int subDirsPerLocalDir;

  public TestShuffleDataContext(int numLocalDirs, int subDirsPerLocalDir) {
    this.localDirs = new String[numLocalDirs];
    this.subDirsPerLocalDir = subDirsPerLocalDir;
  }

  public void create() {
    for (int i = 0; i < localDirs.length; i ++) {
      localDirs[i] = Files.createTempDir().getAbsolutePath();

      for (int p = 0; p < subDirsPerLocalDir; p ++) {
        new File(localDirs[i], String.format("%02x", p)).mkdirs();
      }
    }
  }

  public void cleanup() {
    for (String localDir : localDirs) {
      deleteRecursively(new File(localDir));
    }
  }

  /**
   *  Creates reducer blocks in a sort-based data format within our local dirs.
   *  创建一个基于排序的数据格式在本地目录减速块
   *  */
  public void insertSortShuffleData(int shuffleId, int mapId, byte[][] blocks) throws IOException {
      //mapId对应RDD的partionsID
    String blockId = "shuffle_" + shuffleId + "_" + mapId + "_0";

    OutputStream dataStream = new FileOutputStream(
      ExternalShuffleBlockResolver.getFile(localDirs, subDirsPerLocalDir, blockId + ".data"));
    DataOutputStream indexStream = new DataOutputStream(new FileOutputStream(
      ExternalShuffleBlockResolver.getFile(localDirs, subDirsPerLocalDir, blockId + ".index")));

    long offset = 0;
    indexStream.writeLong(offset);
    for (byte[] block : blocks) {
      offset += block.length;
      dataStream.write(block);
      indexStream.writeLong(offset);
    }

    dataStream.close();
    indexStream.close();
  }

  /**
   *  Creates reducer blocks in a hash-based data format within our local dirs.
   *  创建一个基于散列的数据格式在本地目录减速块
   *  */
  public void insertHashShuffleData(int shuffleId, int mapId, byte[][] blocks) throws IOException {
    for (int i = 0; i < blocks.length; i ++) {
        //mapId对应RDD的partionsID
      String blockId = "shuffle_" + shuffleId + "_" + mapId + "_" + i;
      Files.write(blocks[i],
        ExternalShuffleBlockResolver.getFile(localDirs, subDirsPerLocalDir, blockId));
    }
  }

  /**
   * Creates an ExecutorShuffleInfo object based on the given shuffle manager which targets this
   * context's directories.
   * 创建一个基于给定的Shuffle管理这方面的executorshuffleinfo目标目录对象。
   */
  public ExecutorShuffleInfo createExecutorInfo(String shuffleManager) {
    return new ExecutorShuffleInfo(localDirs, subDirsPerLocalDir, shuffleManager);
  }

  private static void deleteRecursively(File f) {
    assert f != null;
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursively(child);
        }
      }
    }
    f.delete();
  }
}
