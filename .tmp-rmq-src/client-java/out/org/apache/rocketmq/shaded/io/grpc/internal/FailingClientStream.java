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
import org.apache.rocketmq.shaded.io.grpc.ClientStreamTracer;
import org.apache.rocketmq.shaded.io.grpc.Metadata;
import org.apache.rocketmq.shaded.io.grpc.Status;
import org.apache.rocketmq.shaded.io.grpc.internal.ClientStreamListener.RpcProgress;

/**
 * An implementation of {@link ClientStream} that fails (by calling {@link
 * ClientStreamListener#closed}) when started, and silently does nothing for the other operations.
 */
public final class FailingClientStream extends NoopClientStream {
  private boolean started;
  private final Status error;
  private final RpcProgress rpcProgress;
  private final ClientStreamTracer[] tracers;

  /**
   * Creates a {@code FailingClientStream} that would fail with the given error.
   */
  public FailingClientStream(Status error, ClientStreamTracer[] tracers) {
    this(error, RpcProgress.PROCESSED, tracers);
  }

  /**
   * Creates a {@code FailingClientStream} that would fail with the given error.
   */
  public FailingClientStream(
      Status error, RpcProgress rpcProgress, ClientStreamTracer[] tracers) {
    Preconditions.checkArgument(!error.isOk(), "error must not be OK");
    this.error = error;
    this.rpcProgress = rpcProgress;
    this.tracers = tracers;
  }

  @Override
  public void start(ClientStreamListener listener) {
    Preconditions.checkState(!started, "already started");
    started = true;
    for (ClientStreamTracer tracer : tracers) {
      tracer.streamClosed(error);
    }
    listener.closed(error, rpcProgress, new Metadata());
  }

  @VisibleForTesting
  Status getError() {
    return error;
  }

  @Override
  public void appendTimeoutInsight(InsightBuilder insight) {
    insight.appendKeyValue("error", error).appendKeyValue("progress", rpcProgress);
  }
}
