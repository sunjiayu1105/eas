/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.grpc;

import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;
import org.apache.rocketmq.shaded.io.grpc.ManagedChannel;
import org.apache.rocketmq.shaded.io.grpc.Status;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.MeterProvider;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.ExporterMetrics;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Marshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.CompletableResultCode;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.internal.ThrottlingLogger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link GrpcExporter} which uses the standard grpc-java library.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class DefaultGrpcExporter<T extends Marshaler> implements GrpcExporter<T> {

  private static final Logger internalLogger =
      Logger.getLogger(DefaultGrpcExporter.class.getName());

  private final ThrottlingLogger logger = new ThrottlingLogger(internalLogger);

  // We only log unavailable once since it's a configuration issue that won't be recovered.
  private final AtomicBoolean loggedUnimplemented = new AtomicBoolean();

  private final String type;
  private final ExporterMetrics exporterMetrics;
  private final ManagedChannel managedChannel;
  private final MarshalerServiceStub<T, ?, ?> stub;
  private final long timeoutNanos;

  /** Creates a new {@link DefaultGrpcExporter}. */
  DefaultGrpcExporter(
      String type,
      ManagedChannel channel,
      MarshalerServiceStub<T, ?, ?> stub,
      MeterProvider meterProvider,
      long timeoutNanos) {
    this.type = type;
    this.exporterMetrics = ExporterMetrics.createGrpc(type, meterProvider);
    this.managedChannel = channel;
    this.timeoutNanos = timeoutNanos;
    this.stub = stub;
  }

  @Override
  public CompletableResultCode export(T exportRequest, int numItems) {
    exporterMetrics.addSeen(numItems);

    CompletableResultCode result = new CompletableResultCode();

    MarshalerServiceStub<T, ?, ?> stub = this.stub;
    if (timeoutNanos > 0) {
      stub = stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS);
    }
    Futures.addCallback(
        stub.export(exportRequest),
        new FutureCallback<Object>() {
          @Override
          public void onSuccess(@Nullable Object unused) {
            exporterMetrics.addSuccess(numItems);
            result.succeed();
          }

          @Override
          public void onFailure(Throwable t) {
            exporterMetrics.addFailed(numItems);
            Status status = Status.fromThrowable(t);
            switch (status.getCode()) {
              case UNIMPLEMENTED:
                if (loggedUnimplemented.compareAndSet(false, true)) {
                  GrpcExporterUtil.logUnimplemented(internalLogger, type, status.getDescription());
                }
                break;
              case UNAVAILABLE:
                logger.log(
                    Level.SEVERE,
                    "Failed to export "
                        + type
                        + "s. Server is UNAVAILABLE. "
                        + "Make sure your collector is running and reachable from this network. "
                        + "Full error message:"
                        + status.getDescription());
                break;
              default:
                logger.log(
                    Level.WARNING,
                    "Failed to export "
                        + type
                        + "s. Server responded with gRPC status code "
                        + status.getCode().value()
                        + ". Error message: "
                        + status.getDescription());
                break;
            }
            if (logger.isLoggable(Level.FINEST)) {
              logger.log(Level.FINEST, "Failed to export " + type + "s. Details follow: " + t);
            }
            result.fail();
          }
        },
        MoreExecutors.directExecutor());

    return result;
  }

  @Override
  public CompletableResultCode shutdown() {
    if (managedChannel.isTerminated()) {
      return CompletableResultCode.ofSuccess();
    }
    return ManagedChannelUtil.shutdownChannel(managedChannel);
  }
}
