/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.api.trace;

import org.apache.rocketmq.shaded.io.opentelemetry.context.ContextKey;
import javax.annotation.concurrent.Immutable;

/** Util class to hold on to the key for storing a Span in the Context. */
@Immutable
final class SpanContextKey {
  static final ContextKey<Span> KEY = ContextKey.named("opentelemetry-trace-span-key");

  private SpanContextKey() {}
}
