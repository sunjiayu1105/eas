/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.LongExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.concurrent.AdderUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.concurrent.LongAdder;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.InstrumentDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.MetricDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar.ExemplarReservoir;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.resources.Resource;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sum aggregator that keeps values as {@code long}s.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class LongSumAggregator
    extends AbstractSumAggregator<LongAccumulation, LongExemplarData> {

  private final Supplier<ExemplarReservoir<LongExemplarData>> reservoirSupplier;

  public LongSumAggregator(
      InstrumentDescriptor instrumentDescriptor,
      Supplier<ExemplarReservoir<LongExemplarData>> reservoirSupplier) {
    super(instrumentDescriptor);
    this.reservoirSupplier = reservoirSupplier;
  }

  @Override
  public AggregatorHandle<LongAccumulation, LongExemplarData> createHandle() {
    return new Handle(reservoirSupplier.get());
  }

  @Override
  public LongAccumulation merge(
      LongAccumulation previousAccumulation, LongAccumulation accumulation) {
    return LongAccumulation.create(
        previousAccumulation.getValue() + accumulation.getValue(), accumulation.getExemplars());
  }

  @Override
  public LongAccumulation diff(
      LongAccumulation previousAccumulation, LongAccumulation accumulation) {
    return LongAccumulation.create(
        accumulation.getValue() - previousAccumulation.getValue(), accumulation.getExemplars());
  }

  @Override
  public MetricData toMetricData(
      Resource resource,
      InstrumentationScopeInfo instrumentationScopeInfo,
      MetricDescriptor descriptor,
      Map<Attributes, LongAccumulation> accumulationByLabels,
      AggregationTemporality temporality,
      long startEpochNanos,
      long lastCollectionEpoch,
      long epochNanos) {
    return ImmutableMetricData.createLongSum(
        resource,
        instrumentationScopeInfo,
        descriptor.getName(),
        descriptor.getDescription(),
        descriptor.getSourceInstrument().getUnit(),
        ImmutableSumData.create(
            isMonotonic(),
            temporality,
            MetricDataUtils.toLongPointList(
                accumulationByLabels,
                temporality == AggregationTemporality.CUMULATIVE
                    ? startEpochNanos
                    : lastCollectionEpoch,
                epochNanos)));
  }

  static final class Handle extends AggregatorHandle<LongAccumulation, LongExemplarData> {
    private final LongAdder current = AdderUtil.createLongAdder();

    Handle(ExemplarReservoir<LongExemplarData> exemplarReservoir) {
      super(exemplarReservoir);
    }

    @Override
    protected LongAccumulation doAccumulateThenReset(List<LongExemplarData> exemplars) {
      return LongAccumulation.create(this.current.sumThenReset(), exemplars);
    }

    @Override
    public void doRecordLong(long value) {
      current.add(value);
    }
  }
}
