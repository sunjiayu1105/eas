/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data;

import org.apache.rocketmq.shaded.com.google.auto.value.AutoValue;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.ValueAtQuantile;
import javax.annotation.concurrent.Immutable;

/**
 * A summary metric value.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time
 */
@Immutable
@AutoValue
public abstract class ImmutableValueAtQuantile implements ValueAtQuantile {
  public static ValueAtQuantile create(double quantile, double value) {
    return new AutoValue_ImmutableValueAtQuantile(quantile, value);
  }

  ImmutableValueAtQuantile() {}
}
