/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace;

import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.SpanBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.Tracer;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.TracerProvider;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;

/** {@link SdkTracer} is SDK implementation of {@link Tracer}. */
final class SdkTracer implements Tracer {
  static final String FALLBACK_SPAN_NAME = "<unspecified span name>";

  private final TracerSharedState sharedState;
  private final InstrumentationScopeInfo instrumentationScopeInfo;

  SdkTracer(TracerSharedState sharedState, InstrumentationScopeInfo instrumentationScopeInfo) {
    this.sharedState = sharedState;
    this.instrumentationScopeInfo = instrumentationScopeInfo;
  }

  @Override
  public SpanBuilder spanBuilder(String spanName) {
    if (spanName == null || spanName.trim().isEmpty()) {
      spanName = FALLBACK_SPAN_NAME;
    }
    if (sharedState.hasBeenShutdown()) {
      Tracer tracer = TracerProvider.noop().get(instrumentationScopeInfo.getName());
      return tracer.spanBuilder(spanName);
    }
    return new SdkSpanBuilder(
        spanName, instrumentationScopeInfo, sharedState, sharedState.getSpanLimits());
  }

  // Visible for testing
  InstrumentationScopeInfo getInstrumentationScopeInfo() {
    return instrumentationScopeInfo;
  }
}
