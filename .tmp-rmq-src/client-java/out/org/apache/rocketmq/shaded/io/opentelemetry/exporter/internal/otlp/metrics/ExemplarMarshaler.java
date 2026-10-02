/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.SpanContext;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.ProtoFieldInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.KeyValueMarshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.DoubleExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.ExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.LongExemplarData;
import java.io.IOException;
import java.util.List;

final class ExemplarMarshaler extends MarshalerWithSize {

  private final long timeUnixNano;

  private final ExemplarData value;
  private final ProtoFieldInfo valueField;

  private final SpanContext spanContext;

  private final KeyValueMarshaler[] filteredAttributeMarshalers;

  static ExemplarMarshaler[] createRepeated(List<? extends ExemplarData> exemplars) {
    int numExemplars = exemplars.size();
    ExemplarMarshaler[] marshalers = new ExemplarMarshaler[numExemplars];
    for (int i = 0; i < numExemplars; i++) {
      marshalers[i] = ExemplarMarshaler.create(exemplars.get(i));
    }
    return marshalers;
  }

  private static ExemplarMarshaler create(ExemplarData exemplar) {
    KeyValueMarshaler[] attributeMarshalers =
        KeyValueMarshaler.createRepeated(exemplar.getFilteredAttributes());

    ProtoFieldInfo valueField;
    if (exemplar instanceof LongExemplarData) {
      valueField = org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.AS_INT;
    } else {
      assert exemplar instanceof DoubleExemplarData;
      valueField = org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.AS_DOUBLE;
    }

    return new ExemplarMarshaler(
        exemplar.getEpochNanos(),
        exemplar,
        valueField,
        exemplar.getSpanContext(),
        attributeMarshalers);
  }

  private ExemplarMarshaler(
      long timeUnixNano,
      ExemplarData value,
      ProtoFieldInfo valueField,
      SpanContext spanContext,
      KeyValueMarshaler[] filteredAttributeMarshalers) {
    super(calculateSize(timeUnixNano, valueField, value, spanContext, filteredAttributeMarshalers));
    this.timeUnixNano = timeUnixNano;
    this.value = value;
    this.valueField = valueField;
    this.spanContext = spanContext;
    this.filteredAttributeMarshalers = filteredAttributeMarshalers;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeFixed64(
        org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.TIME_UNIX_NANO, timeUnixNano);
    if (valueField == org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.AS_INT) {
      output.serializeFixed64Optional(valueField, ((LongExemplarData) value).getValue());
    } else {
      output.serializeDoubleOptional(valueField, ((DoubleExemplarData) value).getValue());
    }
    if (spanContext.isValid()) {
      output.serializeSpanId(
          org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.SPAN_ID, spanContext.getSpanId());
      output.serializeTraceId(
          org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.TRACE_ID, spanContext.getTraceId());
    }
    output.serializeRepeatedMessage(
        org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.FILTERED_ATTRIBUTES,
        filteredAttributeMarshalers);
  }

  private static int calculateSize(
      long timeUnixNano,
      ProtoFieldInfo valueField,
      ExemplarData value,
      SpanContext spanContext,
      KeyValueMarshaler[] filteredAttributeMarshalers) {
    int size = 0;
    size +=
        MarshalerUtil.sizeFixed64(
            org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.TIME_UNIX_NANO, timeUnixNano);
    if (valueField == org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.AS_INT) {
      size += MarshalerUtil.sizeFixed64Optional(valueField, ((LongExemplarData) value).getValue());
    } else {
      size += MarshalerUtil.sizeDoubleOptional(valueField, ((DoubleExemplarData) value).getValue());
    }
    if (spanContext.isValid()) {
      size +=
          MarshalerUtil.sizeSpanId(
              org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.SPAN_ID, spanContext.getSpanId());
      size +=
          MarshalerUtil.sizeTraceId(
              org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.TRACE_ID,
              spanContext.getTraceId());
    }
    size +=
        MarshalerUtil.sizeRepeatedMessage(
            org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Exemplar.FILTERED_ATTRIBUTES,
            filteredAttributeMarshalers);
    return size;
  }
}
