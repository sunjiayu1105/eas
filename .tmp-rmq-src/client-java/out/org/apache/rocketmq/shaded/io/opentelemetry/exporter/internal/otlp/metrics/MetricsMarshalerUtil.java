/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.ProtoEnumInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.AggregationTemporality;

final class MetricsMarshalerUtil {

  static ProtoEnumInfo mapToTemporality(
      org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.AggregationTemporality temporality) {
    switch (temporality) {
      case CUMULATIVE:
        return AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE;
      case DELTA:
        return AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA;
    }
    // NB: Should not be possible with aligned versions.
    return AggregationTemporality.AGGREGATION_TEMPORALITY_UNSPECIFIED;
  }

  private MetricsMarshalerUtil() {}
}
