/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.traces;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Marshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.collector.trace.v1.internal.ExportTraceServiceRequest;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.util.Collection;

/**
 * {@link Marshaler} to convert SDK {@link SpanData} to OTLP ExportTraceServiceRequest.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class TraceRequestMarshaler extends MarshalerWithSize {

  private final ResourceSpansMarshaler[] resourceSpansMarshalers;

  /**
   * Returns a {@link TraceRequestMarshaler} that can be used to convert the provided {@link
   * SpanData} into a serialized OTLP ExportTraceServiceRequest.
   */
  public static TraceRequestMarshaler create(Collection<SpanData> spanDataList) {
    return new TraceRequestMarshaler(ResourceSpansMarshaler.create(spanDataList));
  }

  private TraceRequestMarshaler(ResourceSpansMarshaler[] resourceSpansMarshalers) {
    super(
        MarshalerUtil.sizeRepeatedMessage(
            ExportTraceServiceRequest.RESOURCE_SPANS, resourceSpansMarshalers));
    this.resourceSpansMarshalers = resourceSpansMarshalers;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeRepeatedMessage(
        ExportTraceServiceRequest.RESOURCE_SPANS, resourceSpansMarshalers);
  }
}
