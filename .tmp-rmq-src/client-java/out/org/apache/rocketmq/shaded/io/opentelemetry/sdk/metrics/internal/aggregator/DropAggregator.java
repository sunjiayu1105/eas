/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.DoubleExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.MetricDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar.ExemplarReservoir;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.resources.Resource;
import java.util.List;
import java.util.Map;

/**
 * A "null object" Aggregator which denotes no aggregation should occur.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class DropAggregator implements Aggregator<Object, DoubleExemplarData> {

  private static final Object ACCUMULATION = new Object();

  public static final Aggregator<Object, DoubleExemplarData> INSTANCE = new DropAggregator();

  private static final AggregatorHandle<Object, DoubleExemplarData> HANDLE =
      new AggregatorHandle<Object, DoubleExemplarData>(ExemplarReservoir.doubleNoSamples()) {
        @Override
        protected void doRecordLong(long value) {}

        @Override
        protected void doRecordDouble(double value) {}

        @Override
        protected Object doAccumulateThenReset(List<DoubleExemplarData> exemplars) {
          return ACCUMULATION;
        }
      };

  private DropAggregator() {}

  @Override
  public AggregatorHandle<Object, DoubleExemplarData> createHandle() {
    return HANDLE;
  }

  @Override
  public Object merge(Object previousAccumulation, Object accumulation) {
    return ACCUMULATION;
  }

  @Override
  public Object diff(Object previousAccumulation, Object accumulation) {
    return ACCUMULATION;
  }

  @Override
  public MetricData toMetricData(
      Resource resource,
      InstrumentationScopeInfo instrumentationScopeInfo,
      MetricDescriptor descriptor,
      Map<Attributes, Object> accumulationByLabels,
      AggregationTemporality temporality,
      long startEpochNanos,
      long lastCollectionEpoch,
      long epochNanos) {
    return EmptyMetricData.getInstance();
  }
}
