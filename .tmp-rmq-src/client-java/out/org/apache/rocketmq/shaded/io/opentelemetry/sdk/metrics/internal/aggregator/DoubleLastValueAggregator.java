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
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.MetricDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar.ExemplarReservoir;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.resources.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Aggregator that aggregates recorded values by storing the last recorded value.
 *
 * <p>Limitation: The current implementation does not store a time when the value was recorded, so
 * merging multiple LastValueAggregators will not preserve the ordering of records. This is not a
 * problem because LastValueAggregator is currently only available for Observers which record all
 * values once.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
@ThreadSafe
public final class DoubleLastValueAggregator
    implements Aggregator<DoubleAccumulation, DoubleExemplarData> {
  private final Supplier<ExemplarReservoir<DoubleExemplarData>> reservoirSupplier;

  public DoubleLastValueAggregator(
      Supplier<ExemplarReservoir<DoubleExemplarData>> reservoirSupplier) {
    this.reservoirSupplier = reservoirSupplier;
  }

  @Override
  public AggregatorHandle<DoubleAccumulation, DoubleExemplarData> createHandle() {
    return new Handle(reservoirSupplier.get());
  }

  @Override
  public DoubleAccumulation merge(DoubleAccumulation previous, DoubleAccumulation current) {
    return current;
  }

  @Override
  public DoubleAccumulation diff(DoubleAccumulation previous, DoubleAccumulation current) {
    return current;
  }

  @Override
  public MetricData toMetricData(
      Resource resource,
      InstrumentationScopeInfo instrumentationScopeInfo,
      MetricDescriptor descriptor,
      Map<Attributes, DoubleAccumulation> accumulationByLabels,
      AggregationTemporality temporality,
      long startEpochNanos,
      long lastCollectionEpochNanos,
      long epochNanos) {
    // Gauge does not need a start time, but we send one as advised by the data model
    // for identifying resets.
    return ImmutableMetricData.createDoubleGauge(
        resource,
        instrumentationScopeInfo,
        descriptor.getName(),
        descriptor.getDescription(),
        descriptor.getSourceInstrument().getUnit(),
        ImmutableGaugeData.create(
            MetricDataUtils.toDoublePointList(
                accumulationByLabels,
                (temporality == AggregationTemporality.CUMULATIVE)
                    ? startEpochNanos
                    : lastCollectionEpochNanos,
                epochNanos)));
  }

  static final class Handle extends AggregatorHandle<DoubleAccumulation, DoubleExemplarData> {
    @Nullable private static final Double DEFAULT_VALUE = null;
    private final AtomicReference<Double> current = new AtomicReference<>(DEFAULT_VALUE);

    private Handle(ExemplarReservoir<DoubleExemplarData> reservoir) {
      super(reservoir);
    }

    @Override
    protected DoubleAccumulation doAccumulateThenReset(List<DoubleExemplarData> exemplars) {
      return DoubleAccumulation.create(this.current.getAndSet(DEFAULT_VALUE), exemplars);
    }

    @Override
    protected void doRecordDouble(double value) {
      current.set(value);
    }
  }
}
