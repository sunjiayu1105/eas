/*
 * Copyright 2016 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.shaded.io.grpc.internal;

import org.apache.rocketmq.shaded.com.google.common.annotations.VisibleForTesting;
import org.apache.rocketmq.shaded.com.google.common.base.Preconditions;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture;
import org.apache.rocketmq.shaded.io.grpc.CallOptions;
import org.apache.rocketmq.shaded.io.grpc.ClientStreamTracer;
import org.apache.rocketmq.shaded.io.grpc.InternalChannelz.SocketStats;
import org.apache.rocketmq.shaded.io.grpc.InternalLogId;
import org.apache.rocketmq.shaded.io.grpc.Metadata;
import org.apache.rocketmq.shaded.io.grpc.MethodDescriptor;
import org.apache.rocketmq.shaded.io.grpc.Status;
import org.apache.rocketmq.shaded.io.grpc.internal.ClientStreamListener.RpcProgress;
import java.util.concurrent.Executor;

/**
 * A client transport that creates streams that will immediately fail when started.
 */
class FailingClientTransport implements ClientTransport {
  @VisibleForTesting
  final Status error;
  private final RpcProgress rpcProgress;

  FailingClientTransport(Status error, RpcProgress rpcProgress) {
    Preconditions.checkArgument(!error.isOk(), "error must not be OK");
    this.error = error;
    this.rpcProgress = rpcProgress;
  }

  @Override
  public ClientStream newStream(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions,
      ClientStreamTracer[] tracers) {
    return new FailingClientStream(error, rpcProgress, tracers);
  }

  @Override
  public void ping(final PingCallback callback, Executor executor) {
    executor.execute(new Runnable() {
        @Override public void run() {
          callback.onFailure(error.asException());
        }
      });
  }

  @Override
  public ListenableFuture<SocketStats> getStats() {
    SettableFuture<SocketStats> ret = SettableFuture.create();
    ret.set(null);
    return ret;
  }

  @Override
  public InternalLogId getLogId() {
    throw new UnsupportedOperationException("Not a real transport");
  }
}
