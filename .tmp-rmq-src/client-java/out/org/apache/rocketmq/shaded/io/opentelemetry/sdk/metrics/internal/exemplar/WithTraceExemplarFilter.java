/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.Span;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;

/** Exemplar sampler that only samples measurements with associated sampled traces. */
final class WithTraceExemplarFilter implements ExemplarFilter {

  static final ExemplarFilter INSTANCE = new WithTraceExemplarFilter();

  private WithTraceExemplarFilter() {}

  @Override
  public boolean shouldSampleMeasurement(long value, Attributes attributes, Context context) {
    return hasSampledTrace(context);
  }

  @Override
  public boolean shouldSampleMeasurement(double value, Attributes attributes, Context context) {
    return hasSampledTrace(context);
  }

  private static boolean hasSampledTrace(Context context) {
    return Span.fromContext(context).getSpanContext().isSampled();
  }
}
