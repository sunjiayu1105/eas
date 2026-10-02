/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.api;

import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.MeterProvider;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.TracerProvider;
import org.apache.rocketmq.shaded.io.opentelemetry.context.propagation.ContextPropagators;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The default OpenTelemetry API, which tries to find API implementations via SPI or otherwise falls
 * back to no-op default implementations.
 */
@ThreadSafe
final class DefaultOpenTelemetry implements OpenTelemetry {
  private static final OpenTelemetry NO_OP = new DefaultOpenTelemetry(ContextPropagators.noop());

  static OpenTelemetry getNoop() {
    return NO_OP;
  }

  static OpenTelemetry getPropagating(ContextPropagators propagators) {
    return new DefaultOpenTelemetry(propagators);
  }

  private final ContextPropagators propagators;

  DefaultOpenTelemetry(ContextPropagators propagators) {
    this.propagators = propagators;
  }

  @Override
  public TracerProvider getTracerProvider() {
    return TracerProvider.noop();
  }

  @Override
  public MeterProvider getMeterProvider() {
    return MeterProvider.noop();
  }

  @Override
  public ContextPropagators getPropagators() {
    return propagators;
  }
}
