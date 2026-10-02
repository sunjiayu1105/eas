/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.otlp.metrics;

import static org.apache.rocketmq.shaded.io.grpc.MethodDescriptor.generateFullMethodName;

import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.io.grpc.CallOptions;
import org.apache.rocketmq.shaded.io.grpc.Channel;
import org.apache.rocketmq.shaded.io.grpc.MethodDescriptor;
import org.apache.rocketmq.shaded.io.grpc.stub.ClientCalls;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.grpc.MarshalerInputStream;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.grpc.MarshalerServiceStub;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import java.io.InputStream;

// Adapted from the protoc generated code for MetricsServiceGrpc.
final class MarshalerMetricsServiceGrpc {

  private static final String SERVICE_NAME =
      "opentelemetry.proto.collector.metrics.v1.MetricsService";

  private static final MethodDescriptor.Marshaller<MetricsRequestMarshaler> REQUEST_MARSHALLER =
      new MethodDescriptor.Marshaller<MetricsRequestMarshaler>() {
        @Override
        public InputStream stream(MetricsRequestMarshaler value) {
          return new MarshalerInputStream(value);
        }

        @Override
        public MetricsRequestMarshaler parse(InputStream stream) {
          throw new UnsupportedOperationException("Only for serializing");
        }
      };

  private static final MethodDescriptor.Marshaller<ExportMetricsServiceResponse>
      RESPONSE_MARSHALER =
          new MethodDescriptor.Marshaller<ExportMetricsServiceResponse>() {
            @Override
            public InputStream stream(ExportMetricsServiceResponse value) {
              throw new UnsupportedOperationException("Only for parsing");
            }

            @Override
            public ExportMetricsServiceResponse parse(InputStream stream) {
              return ExportMetricsServiceResponse.INSTANCE;
            }
          };

  private static final MethodDescriptor<MetricsRequestMarshaler, ExportMetricsServiceResponse>
      getExportMethod =
          MethodDescriptor.<MetricsRequestMarshaler, ExportMetricsServiceResponse>newBuilder()
              .setType(MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Export"))
              .setRequestMarshaller(REQUEST_MARSHALLER)
              .setResponseMarshaller(RESPONSE_MARSHALER)
              .build();

  static MetricsServiceFutureStub newFutureStub(Channel channel) {
    return MetricsServiceFutureStub.newStub(MetricsServiceFutureStub::new, channel);
  }

  static final class MetricsServiceFutureStub
      extends MarshalerServiceStub<
          MetricsRequestMarshaler, ExportMetricsServiceResponse, MetricsServiceFutureStub> {
    private MetricsServiceFutureStub(Channel channel, CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected MarshalerMetricsServiceGrpc.MetricsServiceFutureStub build(
        Channel channel, CallOptions callOptions) {
      return new MarshalerMetricsServiceGrpc.MetricsServiceFutureStub(channel, callOptions);
    }

    @Override
    public ListenableFuture<ExportMetricsServiceResponse> export(MetricsRequestMarshaler request) {
      return ClientCalls.futureUnaryCall(
          getChannel().newCall(getExportMethod, getCallOptions()), request);
    }
  }

  private MarshalerMetricsServiceGrpc() {}
}
