/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.grpc;

import org.apache.rocketmq.shaded.io.grpc.ManagedChannel;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Marshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.CompletableResultCode;
import java.net.URI;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An exporter of a {@link Marshaler} using the gRPC wire format.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public interface GrpcExporter<T extends Marshaler> {

  /** Returns a new {@link GrpcExporterBuilder}. */
  static <T extends Marshaler> GrpcExporterBuilder<T> builder(
      String type,
      long defaultTimeoutSecs,
      URI defaultEndpoint,
      Supplier<Function<ManagedChannel, MarshalerServiceStub<T, ?, ?>>> stubFactory,
      String grpcServiceName,
      String grpcEndpointPath) {
    return GrpcExporterUtil.exporterBuilder(
        type, defaultTimeoutSecs, defaultEndpoint, stubFactory, grpcServiceName, grpcEndpointPath);
  }

  /**
   * Exports the {@code exportRequest} which is a request {@link Marshaler} for {@code numItems}
   * items.
   */
  CompletableResultCode export(T exportRequest, int numItems);

  /** Shuts the exporter down. */
  CompletableResultCode shutdown();
}
