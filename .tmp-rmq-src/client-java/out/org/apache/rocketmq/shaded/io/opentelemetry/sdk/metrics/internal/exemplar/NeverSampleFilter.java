/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;

class NeverSampleFilter implements ExemplarFilter {
  static final ExemplarFilter INSTANCE = new NeverSampleFilter();

  private NeverSampleFilter() {}

  @Override
  public boolean shouldSampleMeasurement(long value, Attributes attributes, Context context) {
    return false;
  }

  @Override
  public boolean shouldSampleMeasurement(double value, Attributes attributes, Context context) {
    return false;
  }
}
