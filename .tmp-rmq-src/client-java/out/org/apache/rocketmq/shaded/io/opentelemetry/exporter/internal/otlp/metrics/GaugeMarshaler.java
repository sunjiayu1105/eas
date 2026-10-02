/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.Gauge;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.GaugeData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.PointData;
import java.io.IOException;

final class GaugeMarshaler extends MarshalerWithSize {
  private final NumberDataPointMarshaler[] dataPoints;

  static GaugeMarshaler create(GaugeData<? extends PointData> gauge) {
    NumberDataPointMarshaler[] dataPointMarshalers =
        NumberDataPointMarshaler.createRepeated(gauge.getPoints());

    return new GaugeMarshaler(dataPointMarshalers);
  }

  private GaugeMarshaler(NumberDataPointMarshaler[] dataPoints) {
    super(calculateSize(dataPoints));
    this.dataPoints = dataPoints;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeRepeatedMessage(Gauge.DATA_POINTS, dataPoints);
  }

  private static int calculateSize(NumberDataPointMarshaler[] dataPoints) {
    int size = 0;
    size += MarshalerUtil.sizeRepeatedMessage(Gauge.DATA_POINTS, dataPoints);
    return size;
  }
}
