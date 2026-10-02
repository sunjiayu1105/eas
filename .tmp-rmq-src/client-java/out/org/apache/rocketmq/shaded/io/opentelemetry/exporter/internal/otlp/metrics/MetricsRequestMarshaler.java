/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Marshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.collector.metrics.v1.internal.ExportMetricsServiceRequest;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import java.io.IOException;
import java.util.Collection;

/**
 * {@link Marshaler} to convert SDK {@link MetricData} to OTLP ExportMetricsServiceRequest.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class MetricsRequestMarshaler extends MarshalerWithSize {

  private final ResourceMetricsMarshaler[] resourceMetricsMarshalers;

  /**
   * Returns a {@link MetricsRequestMarshaler} that can be used to convert the provided {@link
   * MetricData} into a serialized OTLP ExportMetricsServiceRequest.
   */
  public static MetricsRequestMarshaler create(Collection<MetricData> metricDataList) {
    return new MetricsRequestMarshaler(ResourceMetricsMarshaler.create(metricDataList));
  }

  private MetricsRequestMarshaler(ResourceMetricsMarshaler[] resourceMetricsMarshalers) {
    super(calculateSize(resourceMetricsMarshalers));
    this.resourceMetricsMarshalers = resourceMetricsMarshalers;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeRepeatedMessage(
        ExportMetricsServiceRequest.RESOURCE_METRICS, resourceMetricsMarshalers);
  }

  private static int calculateSize(ResourceMetricsMarshaler[] resourceMetricsMarshalers) {
    int size = 0;
    size +=
        MarshalerUtil.sizeRepeatedMessage(
            ExportMetricsServiceRequest.RESOURCE_METRICS, resourceMetricsMarshalers);
    return size;
  }
}
