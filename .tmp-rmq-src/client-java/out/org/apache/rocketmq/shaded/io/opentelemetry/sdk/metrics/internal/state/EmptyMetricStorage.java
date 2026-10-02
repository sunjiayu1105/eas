/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.aggregator.EmptyMetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.MetricDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.export.CollectionInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.resources.Resource;

final class EmptyMetricStorage implements SynchronousMetricStorage {
  static final EmptyMetricStorage INSTANCE = new EmptyMetricStorage();

  private EmptyMetricStorage() {}

  private final MetricDescriptor descriptor = MetricDescriptor.create("", "", "");
  private final BoundStorageHandle emptyHandle =
      new BoundStorageHandle() {
        @Override
        public void recordLong(long value, Attributes attributes, Context context) {}

        @Override
        public void recordDouble(double value, Attributes attributes, Context context) {}

        @Override
        public void release() {}
      };

  @Override
  public MetricDescriptor getMetricDescriptor() {
    return descriptor;
  }

  @Override
  public BoundStorageHandle bind(Attributes attributes) {
    return emptyHandle;
  }

  @Override
  public MetricData collectAndReset(
      CollectionInfo collectionInfo,
      Resource resource,
      InstrumentationScopeInfo instrumentationScopeInfo,
      long startEpochNanos,
      long epochNanos,
      boolean suppressSynchronousCollection) {
    return EmptyMetricData.getInstance();
  }
}
