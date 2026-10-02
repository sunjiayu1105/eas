/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal;

import static org.apache.rocketmq.shaded.io.opentelemetry.api.common.AttributeKey.booleanKey;
import static org.apache.rocketmq.shaded.io.opentelemetry.api.common.AttributeKey.stringKey;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.AttributeKey;
import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.LongCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.Meter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.MeterProvider;

/**
 * Helper for recording metrics from OTLP exporters.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public class ExporterMetrics {

  private static final AttributeKey<String> ATTRIBUTE_KEY_TYPE = stringKey("type");
  private static final AttributeKey<Boolean> ATTRIBUTE_KEY_SUCCESS = booleanKey("success");

  private final LongCounter seen;
  private final LongCounter exported;

  private final Attributes seenAttrs;
  private final Attributes successAttrs;
  private final Attributes failedAttrs;

  private ExporterMetrics(Meter meter, String type) {
    seenAttrs = Attributes.builder().put(ATTRIBUTE_KEY_TYPE, type).build();
    seen = meter.counterBuilder("otlp.exporter.seen").build();
    exported = meter.counterBuilder("otlp.exporter.exported").build();
    successAttrs = seenAttrs.toBuilder().put(ATTRIBUTE_KEY_SUCCESS, true).build();
    failedAttrs = seenAttrs.toBuilder().put(ATTRIBUTE_KEY_SUCCESS, false).build();
  }

  /** Record number of records seen. */
  public void addSeen(long value) {
    seen.add(value, seenAttrs);
  }

  /** Record number of records which successfully exported. */
  public void addSuccess(long value) {
    exported.add(value, successAttrs);
  }

  /** Record number of records which failed to export. */
  public void addFailed(long value) {
    exported.add(value, failedAttrs);
  }

  /** Create an instance for recording OTLP gRPC exporter metrics. */
  public static ExporterMetrics createGrpc(String type, MeterProvider meterProvider) {
    return new ExporterMetrics(meterProvider.get("org.apache.rocketmq.shaded.io.opentelemetry.exporters.otlp-grpc"), type);
  }

  /** Create an instance for recording OTLP gRPC OkHttp exporter metrics. */
  public static ExporterMetrics createGrpcOkHttp(String type, MeterProvider meterProvider) {
    return new ExporterMetrics(
        meterProvider.get("org.apache.rocketmq.shaded.io.opentelemetry.exporters.otlp-grpc-okhttp"), type);
  }

  /** Create an instance for recording OTLP http/protobuf exporter metrics. */
  public static ExporterMetrics createHttpProtobuf(String type, MeterProvider meterProvider) {
    return new ExporterMetrics(meterProvider.get("org.apache.rocketmq.shaded.io.opentelemetry.exporters.otlp-http"), type);
  }
}
