/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.grpc;

import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.io.grpc.CallOptions;
import org.apache.rocketmq.shaded.io.grpc.Channel;
import org.apache.rocketmq.shaded.io.grpc.stub.AbstractFutureStub;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Marshaler;

/**
 * A gRPC stub that uses a {@link Marshaler}.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public abstract class MarshalerServiceStub<
        T extends Marshaler, U, S extends MarshalerServiceStub<T, U, S>>
    extends AbstractFutureStub<S> {
  protected MarshalerServiceStub(Channel channel, CallOptions callOptions) {
    super(channel, callOptions);
  }

  public abstract ListenableFuture<U> export(T request);
}
