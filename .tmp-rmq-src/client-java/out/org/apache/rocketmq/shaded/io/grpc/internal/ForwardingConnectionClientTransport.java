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

import org.apache.rocketmq.shaded.com.google.common.base.MoreObjects;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.io.grpc.Attributes;
import org.apache.rocketmq.shaded.io.grpc.CallOptions;
import org.apache.rocketmq.shaded.io.grpc.ClientStreamTracer;
import org.apache.rocketmq.shaded.io.grpc.InternalChannelz.SocketStats;
import org.apache.rocketmq.shaded.io.grpc.InternalLogId;
import org.apache.rocketmq.shaded.io.grpc.Metadata;
import org.apache.rocketmq.shaded.io.grpc.MethodDescriptor;
import org.apache.rocketmq.shaded.io.grpc.Status;
import java.util.concurrent.Executor;

abstract class ForwardingConnectionClientTransport implements ConnectionClientTransport {
  @Override
  public Runnable start(Listener listener) {
    return delegate().start(listener);
  }

  @Override
  public void shutdown(Status status) {
    delegate().shutdown(status);
  }

  @Override
  public void shutdownNow(Status status) {
    delegate().shutdownNow(status);
  }

  @Override
  public ClientStream newStream(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions,
      ClientStreamTracer[] tracers) {
    return delegate().newStream(method, headers, callOptions, tracers);
  }

  @Override
  public void ping(PingCallback callback, Executor executor) {
    delegate().ping(callback, executor);
  }

  @Override
  public InternalLogId getLogId() {
    return delegate().getLogId();
  }

  @Override
  public Attributes getAttributes() {
    return delegate().getAttributes();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
  }

  @Override
  public ListenableFuture<SocketStats> getStats() {
    return delegate().getStats();
  }

  protected abstract ConnectionClientTransport delegate();
}
