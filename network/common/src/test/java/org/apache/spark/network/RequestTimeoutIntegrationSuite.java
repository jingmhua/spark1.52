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

package org.apache.spark.network;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.buffer.NioManagedBuffer;
import org.apache.spark.network.client.ChunkReceivedCallback;
import org.apache.spark.network.client.RpcResponseCallback;
import org.apache.spark.network.client.TransportClient;
import org.apache.spark.network.client.TransportClientFactory;
import org.apache.spark.network.server.RpcHandler;
import org.apache.spark.network.server.StreamManager;
import org.apache.spark.network.server.TransportServer;
import org.apache.spark.network.util.MapConfigProvider;
import org.apache.spark.network.util.TransportConf;
import org.junit.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Suite which ensures that requests that go without a response for the network timeout period are
 * failed, and the connection closed.
 * 测试套件,确保没有响应的网络超时时间的请求失败，连接关闭
 * In this suite, we use 2 seconds as the connection timeout, with some slack given in the tests,
 * to ensure stability in different test environments.
 * 这是一个测试套件,我们使用2秒作为连接超时,在测试中给出的一些松弛,确保在不同的测试环境中的稳定性
 */
public class RequestTimeoutIntegrationSuite {

  private TransportServer server;
  private TransportClientFactory clientFactory;

  private StreamManager defaultManager;
  private TransportConf conf;

  // A large timeout that "shouldn't happen", for the sake of faulty tests not hanging forever.
    //“不应该发生”的大超时,为了错误的测试,永远不会挂起
  private final int FOREVER = 60 * 1000;

  @Before
  public void setUp() throws Exception {
    Map<String, String> configMap = Maps.newHashMap();
    configMap.put("spark.shuffle.io.connectionTimeout", "2s");
    conf = new TransportConf(new MapConfigProvider(configMap));

    defaultManager = new StreamManager() {
      @Override
      public ManagedBuffer getChunk(long streamId, int chunkIndex) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (clientFactory != null) {
      clientFactory.close();
    }
  }

  // Basic suite: First request completes quickly, and second waits for longer than network timeout.
  //基本套件,第一个请求完成的很快,第二等待时间网络超时
  @Test
  public void timeoutInactiveRequests() throws Exception {
    final Semaphore semaphore = new Semaphore(1);
    final byte[] response = new byte[16];
    RpcHandler handler = new RpcHandler() {
      @Override
      public void receive(TransportClient client, byte[] message, RpcResponseCallback callback) {
        try {
          semaphore.tryAcquire(FOREVER, TimeUnit.MILLISECONDS);
          callback.onSuccess(response);
        } catch (InterruptedException e) {
          // do nothing
        }
      }

      @Override
      public StreamManager getStreamManager() {
        return defaultManager;
      }
    };

    TransportContext context = new TransportContext(conf, handler);
    server = context.createServer();
    clientFactory = context.createClientFactory();
    TransportClient client = clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());

    // First completes quickly (semaphore starts at 1).
    //首先快速完成
    TestCallback callback0 = new TestCallback();
    synchronized (callback0) {
      client.sendRpc(new byte[0], callback0);
      callback0.wait(FOREVER);
      assert (callback0.success.length == response.length);
    }

    // Second times out after 2 seconds, with slack. Must be IOException.
    //第二次2秒后,
    TestCallback callback1 = new TestCallback();
    synchronized (callback1) {
      client.sendRpc(new byte[0], callback1);
      callback1.wait(4 * 1000);
      assert (callback1.failure != null);
      assert (callback1.failure instanceof IOException);
    }
    semaphore.release();
  }

  // A timeout will cause the connection to be closed, invalidating the current TransportClient.
  //超时将导致连接被关闭,无效的当前TransportClient
  // It should be the case that requesting a client from the factory produces a new, valid one.
  //它应该工厂从请求产生一个新的有效客户端
  @Test
  public void timeoutCleanlyClosesClient() throws Exception {
    final Semaphore semaphore = new Semaphore(0);
    final byte[] response = new byte[16];
    RpcHandler handler = new RpcHandler() {
      @Override
      public void receive(TransportClient client, byte[] message, RpcResponseCallback callback) {
        try {
          semaphore.tryAcquire(FOREVER, TimeUnit.MILLISECONDS);
          callback.onSuccess(response);
        } catch (InterruptedException e) {
          // do nothing
        }
      }

      @Override
      public StreamManager getStreamManager() {
        return defaultManager;
      }
    };

    TransportContext context = new TransportContext(conf, handler);
    server = context.createServer();
    clientFactory = context.createClientFactory();

    // First request should eventually fail.
    //第一个请求最终失败
    TransportClient client0 =
      clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());
    TestCallback callback0 = new TestCallback();
    synchronized (callback0) {
      client0.sendRpc(new byte[0], callback0);
      callback0.wait(FOREVER);
      assert (callback0.failure instanceof IOException);
      assert (!client0.isActive());
    }

    // Increment the semaphore and the second request should succeed quickly.
    //增量信号和第二请求应迅速成功。
    semaphore.release(2);
    TransportClient client1 =
      clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());
    TestCallback callback1 = new TestCallback();
    synchronized (callback1) {
      client1.sendRpc(new byte[0], callback1);
      callback1.wait(FOREVER);
      assert (callback1.success.length == response.length);
      assert (callback1.failure == null);
    }
  }

  // The timeout is relative to the LAST request sent, which is kinda weird, but still.
  //超时是相对于发送的最后请求的,这有点奇怪
  // This test also makes sure the timeout works for Fetch requests as well as RPCs.
  //这个测试也会超时工作取请求以及RPCs
  @Test
  public void furtherRequestsDelay() throws Exception {
    final byte[] response = new byte[16];
    final StreamManager manager = new StreamManager() {
      @Override
      public ManagedBuffer getChunk(long streamId, int chunkIndex) {
        Uninterruptibles.sleepUninterruptibly(FOREVER, TimeUnit.MILLISECONDS);
        return new NioManagedBuffer(ByteBuffer.wrap(response));
      }
    };
    RpcHandler handler = new RpcHandler() {
      @Override
      public void receive(TransportClient client, byte[] message, RpcResponseCallback callback) {
        throw new UnsupportedOperationException();
      }

      @Override
      public StreamManager getStreamManager() {
        return manager;
      }
    };

    TransportContext context = new TransportContext(conf, handler);
    server = context.createServer();
    clientFactory = context.createClientFactory();
    TransportClient client = clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());

    // Send one request, which will eventually fail.
    //发送一个请求，这将最终失败。
    TestCallback callback0 = new TestCallback();
    client.fetchChunk(0, 0, callback0);
    Uninterruptibles.sleepUninterruptibly(1200, TimeUnit.MILLISECONDS);

    // Send a second request before the first has failed.
    //在第一个失败之前发送第二个请求。
    TestCallback callback1 = new TestCallback();
    client.fetchChunk(0, 1, callback1);
    Uninterruptibles.sleepUninterruptibly(1200, TimeUnit.MILLISECONDS);

    synchronized (callback0) {
      // not complete yet, but should complete soon
     //还没完成，但很快就要完成了
      assert (callback0.success == null && callback0.failure == null);
      callback0.wait(2 * 1000);
      assert (callback0.failure instanceof IOException);
    }

    synchronized (callback1) {
      // failed at same time as previous
      //失败的同时和以前一样
      assert (callback0.failure instanceof IOException);
    }
  }

  /**
   * Callback which sets 'success' or 'failure' on completion.
   * Additionally notifies all waiters on this callback when invoked.
   * 回调在完成时设置“成功”或“失败”,另外在调用时通知所有这个回调的服务器
   */
  class TestCallback implements RpcResponseCallback, ChunkReceivedCallback {

    byte[] success;
    Throwable failure;

    @Override
    public void onSuccess(byte[] response) {
      synchronized(this) {
        success = response;
        this.notifyAll();
      }
    }

    @Override
    public void onFailure(Throwable e) {
      synchronized(this) {
        failure = e;
        this.notifyAll();
      }
    }

    @Override
    public void onSuccess(int chunkIndex, ManagedBuffer buffer) {
      synchronized(this) {
        try {
          success = buffer.nioByteBuffer().array();
          this.notifyAll();
        } catch (IOException e) {
          // weird
        }
      }
    }

    @Override
    public void onFailure(int chunkIndex, Throwable e) {
      synchronized(this) {
        failure = e;
        this.notifyAll();
      }
    }
  }
}
