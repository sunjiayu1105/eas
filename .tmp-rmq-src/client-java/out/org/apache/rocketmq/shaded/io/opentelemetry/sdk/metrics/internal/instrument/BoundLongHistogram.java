/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.instrument;

import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.LongHistogram;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A histogram instrument that records {@code long} values with pre-associated attributes.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
@ThreadSafe
public interface BoundLongHistogram {
  /**
   * Records a value with a pre-bound set of attributes.
   *
   * <p>Note: This may use {@code Context.current()} to pull the context associated with this
   * measurement.
   *
   * @param value The amount of the measurement.
   */
  void record(long value);

  /**
   * Records a value with a pre-bound set of attributes.
   *
   * @param value The amount of the measurement.
   * @param context The explicit context to associate with this measurement.
   */
  void record(long value, Context context);

  /**
   * Unbinds the current bound instance from the {@link LongHistogram}.
   *
   * <p>After this method returns the current instance is considered invalid (not being managed by
   * the instrument). This frees any reserved memory.
   */
  void unbind();
}
