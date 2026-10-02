/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state;

import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.View;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.ExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.Aggregator;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.AggregatorFactory;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.InstrumentDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.MetricDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar.ExemplarFilter;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.view.RegisteredView;

/**
 * Stores aggregated {@link MetricData} for synchronous instruments.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public interface SynchronousMetricStorage extends MetricStorage, WriteableMetricStorage {

  /** Returns metric storage which doesn't store or generate any metrics. */
  static SynchronousMetricStorage empty() {
    return EmptyMetricStorage.INSTANCE;
  }

  /**
   * Constructs metric storage for a given synchronous instrument and view.
   *
   * @return The storage, or {@link EmptyMetricStorage#empty()} if the instrument should not be
   *     recorded.
   */
  static <T, U extends ExemplarData> SynchronousMetricStorage create(
      RegisteredView registeredView,
      InstrumentDescriptor instrumentDescriptor,
      ExemplarFilter exemplarFilter) {
    View view = registeredView.getView();
    MetricDescriptor metricDescriptor =
        MetricDescriptor.create(view, registeredView.getViewSourceInfo(), instrumentDescriptor);
    Aggregator<T, U> aggregator =
        ((AggregatorFactory) view.getAggregation())
            .createAggregator(instrumentDescriptor, exemplarFilter);
    // We won't be storing this metric.
    if (Aggregator.drop() == aggregator) {
      return empty();
    }
    return new DefaultSynchronousMetricStorage<>(
        metricDescriptor, aggregator, registeredView.getViewAttributesProcessor());
  }
}
