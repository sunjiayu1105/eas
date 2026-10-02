/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.ExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.Aggregator;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.MetricDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.export.CollectionInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.view.AttributesProcessor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.resources.Resource;
import java.util.Map;
import java.util.Objects;

/**
 * Stores aggregated {@link MetricData} for synchronous instruments.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class DefaultSynchronousMetricStorage<T, U extends ExemplarData>
    implements SynchronousMetricStorage {
  private final MetricDescriptor metricDescriptor;
  private final DeltaMetricStorage<T, U> deltaMetricStorage;
  private final TemporalMetricStorage<T, U> temporalMetricStorage;
  private final AttributesProcessor attributesProcessor;

  DefaultSynchronousMetricStorage(
      MetricDescriptor metricDescriptor,
      Aggregator<T, U> aggregator,
      AttributesProcessor attributesProcessor) {
    this.attributesProcessor = attributesProcessor;
    this.metricDescriptor = metricDescriptor;
    this.deltaMetricStorage =
        new DeltaMetricStorage<>(aggregator, metricDescriptor.getSourceInstrument());
    this.temporalMetricStorage = new TemporalMetricStorage<>(aggregator, /* isSynchronous= */ true);
  }

  // This is a storage handle to use when the attributes processor requires
  private final BoundStorageHandle lateBoundStorageHandle =
      new BoundStorageHandle() {
        @Override
        public void release() {}

        @Override
        public void recordLong(long value, Attributes attributes, Context context) {
          DefaultSynchronousMetricStorage.this.recordLong(value, attributes, context);
        }

        @Override
        public void recordDouble(double value, Attributes attributes, Context context) {
          DefaultSynchronousMetricStorage.this.recordDouble(value, attributes, context);
        }
      };

  @Override
  public BoundStorageHandle bind(Attributes attributes) {
    Objects.requireNonNull(attributes, "attributes");
    if (attributesProcessor.usesContext()) {
      // We cannot pre-bind attributes because we need to pull attributes from context.
      return lateBoundStorageHandle;
    }
    return deltaMetricStorage.bind(attributesProcessor.process(attributes, Context.current()));
  }

  // Overridden to make sure attributes processor can pull baggage.
  @Override
  public void recordLong(long value, Attributes attributes, Context context) {
    Objects.requireNonNull(attributes, "attributes");
    attributes = attributesProcessor.process(attributes, context);
    BoundStorageHandle handle = deltaMetricStorage.bind(attributes);
    try {
      handle.recordLong(value, attributes, context);
    } finally {
      handle.release();
    }
  }

  // Overridden to make sure attributes processor can pull baggage.
  @Override
  public void recordDouble(double value, Attributes attributes, Context context) {
    Objects.requireNonNull(attributes, "attributes");
    attributes = attributesProcessor.process(attributes, context);
    BoundStorageHandle handle = deltaMetricStorage.bind(attributes);
    try {
      handle.recordDouble(value, attributes, context);
    } finally {
      handle.release();
    }
  }

  @Override
  public MetricData collectAndReset(
      CollectionInfo collectionInfo,
      Resource resource,
      InstrumentationScopeInfo instrumentationScopeInfo,
      long startEpochNanos,
      long epochNanos,
      boolean suppressSynchronousCollection) {
    AggregationTemporality temporality =
        collectionInfo.getAggregationTemporality(
            getMetricDescriptor().getSourceInstrument().getType());
    Map<Attributes, T> result =
        deltaMetricStorage.collectFor(
            collectionInfo.getCollector(),
            collectionInfo.getAllCollectors(),
            suppressSynchronousCollection);
    return temporalMetricStorage.buildMetricFor(
        collectionInfo.getCollector(),
        resource,
        instrumentationScopeInfo,
        getMetricDescriptor(),
        temporality,
        result,
        startEpochNanos,
        epochNanos);
  }

  @Override
  public MetricDescriptor getMetricDescriptor() {
    return metricDescriptor;
  }
}
