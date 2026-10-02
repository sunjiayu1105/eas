/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;

class AlwaysSampleFilter implements ExemplarFilter {
  static final ExemplarFilter INSTANCE = new AlwaysSampleFilter();

  private AlwaysSampleFilter() {}

  @Override
  public boolean shouldSampleMeasurement(long value, Attributes attributes, Context context) {
    return true;
  }

  @Override
  public boolean shouldSampleMeasurement(double value, Attributes attributes, Context context) {
    return true;
  }
}
