/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.ProtoEnumInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Histogram;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.HistogramData;
import java.io.IOException;

final class HistogramMarshaler extends MarshalerWithSize {
  private final HistogramDataPointMarshaler[] dataPoints;
  private final ProtoEnumInfo aggregationTemporality;

  static HistogramMarshaler create(HistogramData histogram) {
    HistogramDataPointMarshaler[] dataPointMarshalers =
        HistogramDataPointMarshaler.createRepeated(histogram.getPoints());
    return new HistogramMarshaler(
        dataPointMarshalers,
        MetricsMarshalerUtil.mapToTemporality(histogram.getAggregationTemporality()));
  }

  private HistogramMarshaler(
      HistogramDataPointMarshaler[] dataPoints, ProtoEnumInfo aggregationTemporality) {
    super(calculateSize(dataPoints, aggregationTemporality));
    this.dataPoints = dataPoints;
    this.aggregationTemporality = aggregationTemporality;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeRepeatedMessage(Histogram.DATA_POINTS, dataPoints);
    output.serializeEnum(Histogram.AGGREGATION_TEMPORALITY, aggregationTemporality);
  }

  private static int calculateSize(
      HistogramDataPointMarshaler[] dataPoints, ProtoEnumInfo aggregationTemporality) {
    int size = 0;
    size += MarshalerUtil.sizeRepeatedMessage(Histogram.DATA_POINTS, dataPoints);
    size += MarshalerUtil.sizeEnum(Histogram.AGGREGATION_TEMPORALITY, aggregationTemporality);
    return size;
  }
}
