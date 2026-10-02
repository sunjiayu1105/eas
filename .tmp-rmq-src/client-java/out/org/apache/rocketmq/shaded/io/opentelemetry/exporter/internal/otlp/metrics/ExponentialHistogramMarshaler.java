/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.ProtoEnumInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.ExponentialHistogram;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data.exponentialhistogram.ExponentialHistogramData;
import java.io.IOException;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public class ExponentialHistogramMarshaler extends MarshalerWithSize {
  private final ExponentialHistogramDataPointMarshaler[] dataPoints;
  private final ProtoEnumInfo aggregationTemporality;

  static ExponentialHistogramMarshaler create(ExponentialHistogramData histogramData) {
    ExponentialHistogramDataPointMarshaler[] dataPoints =
        ExponentialHistogramDataPointMarshaler.createRepeated(histogramData.getPoints());
    return new ExponentialHistogramMarshaler(
        dataPoints,
        MetricsMarshalerUtil.mapToTemporality(histogramData.getAggregationTemporality()));
  }

  private ExponentialHistogramMarshaler(
      ExponentialHistogramDataPointMarshaler[] dataPointMarshalers,
      ProtoEnumInfo aggregationTemporality) {
    super(calculateSize(dataPointMarshalers, aggregationTemporality));
    this.dataPoints = dataPointMarshalers;
    this.aggregationTemporality = aggregationTemporality;
  }

  @Override
  protected void writeTo(Serializer output) throws IOException {
    output.serializeRepeatedMessage(ExponentialHistogram.DATA_POINTS, dataPoints);
    output.serializeEnum(ExponentialHistogram.AGGREGATION_TEMPORALITY, aggregationTemporality);
  }

  private static int calculateSize(
      ExponentialHistogramDataPointMarshaler[] dataPointMarshalers,
      ProtoEnumInfo aggregationTemporality) {
    int size = 0;
    size +=
        MarshalerUtil.sizeRepeatedMessage(ExponentialHistogram.DATA_POINTS, dataPointMarshalers);
    size +=
        MarshalerUtil.sizeEnum(
            ExponentialHistogram.AGGREGATION_TEMPORALITY, aggregationTemporality);
    return size;
  }
}
