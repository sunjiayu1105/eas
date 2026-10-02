/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Marshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.InstrumentationScopeMarshaller;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.ScopeMetrics;
import java.io.IOException;
import java.util.List;

final class InstrumentationScopeMetricsMarshaler extends MarshalerWithSize {
  private final InstrumentationScopeMarshaller instrumentationScope;
  private final List<Marshaler> metricMarshalers;
  private final byte[] schemaUrlUtf8;

  InstrumentationScopeMetricsMarshaler(
      InstrumentationScopeMarshaller instrumentationScope,
      byte[] schemaUrlUtf8,
      List<Marshaler> metricMarshalers) {
    super(calculateSize(instrumentationScope, schemaUrlUtf8, metricMarshalers));
    this.instrumentationScope = instrumentationScope;
    this.schemaUrlUtf8 = schemaUrlUtf8;
    this.metricMarshalers = metricMarshalers;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeMessage(ScopeMetrics.SCOPE, instrumentationScope);
    output.serializeRepeatedMessage(ScopeMetrics.METRICS, metricMarshalers);
    output.serializeString(ScopeMetrics.SCHEMA_URL, schemaUrlUtf8);
  }

  private static int calculateSize(
      InstrumentationScopeMarshaller instrumentationScope,
      byte[] schemaUrlUtf8,
      List<Marshaler> metricMarshalers) {
    int size = 0;
    size += MarshalerUtil.sizeMessage(ScopeMetrics.SCOPE, instrumentationScope);
    size += MarshalerUtil.sizeBytes(ScopeMetrics.SCHEMA_URL, schemaUrlUtf8);
    size += MarshalerUtil.sizeRepeatedMessage(ScopeMetrics.METRICS, metricMarshalers);
    return size;
  }
}
