/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.view;

import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.Clock;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.Aggregation;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.ExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.Aggregator;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.AggregatorFactory;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.DoubleExplicitBucketHistogramAggregator;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.ExplicitBucketHistogramUtils;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.InstrumentDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar.ExemplarFilter;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar.ExemplarReservoir;
import java.util.List;

/**
 * Explicit bucket histogram aggregation configuration.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ExplicitBucketHistogramAggregation implements Aggregation, AggregatorFactory {

  private static final Aggregation DEFAULT =
      new ExplicitBucketHistogramAggregation(
          ExplicitBucketHistogramUtils.DEFAULT_HISTOGRAM_BUCKET_BOUNDARIES);

  public static Aggregation getDefault() {
    return DEFAULT;
  }

  public static Aggregation create(List<Double> bucketBoundaries) {
    return new ExplicitBucketHistogramAggregation(bucketBoundaries);
  }

  private final List<Double> bucketBoundaries;
  private final double[] bucketBoundaryArray;

  private ExplicitBucketHistogramAggregation(List<Double> bucketBoundaries) {
    this.bucketBoundaries = bucketBoundaries;
    // We need to fail here if our bucket boundaries are ill-configured.
    this.bucketBoundaryArray = ExplicitBucketHistogramUtils.createBoundaryArray(bucketBoundaries);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T, U extends ExemplarData> Aggregator<T, U> createAggregator(
      InstrumentDescriptor instrumentDescriptor, ExemplarFilter exemplarFilter) {
    return (Aggregator<T, U>)
        new DoubleExplicitBucketHistogramAggregator(
            bucketBoundaryArray,
            () ->
                ExemplarReservoir.filtered(
                    exemplarFilter,
                    ExemplarReservoir.histogramBucketReservoir(
                        Clock.getDefault(), bucketBoundaries)));
  }

  @Override
  public boolean isCompatibleWithInstrument(InstrumentDescriptor instrumentDescriptor) {
    switch (instrumentDescriptor.getType()) {
      case COUNTER:
      case HISTOGRAM:
        return true;
      default:
        return false;
    }
  }

  @Override
  public String toString() {
    return "ExplicitBucketHistogramAggregation(" + bucketBoundaries.toString() + ")";
  }
}
