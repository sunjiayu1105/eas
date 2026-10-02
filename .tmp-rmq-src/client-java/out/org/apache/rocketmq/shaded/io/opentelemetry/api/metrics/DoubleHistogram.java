/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.api.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import javax.annotation.concurrent.ThreadSafe;

/** A histogram instrument that records {@code long} values. */
@ThreadSafe
public interface DoubleHistogram {

  /**
   * Records a value.
   *
   * <p>Note: This may use {@code Context.current()} to pull the context associated with this
   * measurement.
   *
   * @param value The amount of the measurement.
   */
  void record(double value);

  /**
   * Records a value with a set of attributes.
   *
   * <p>Note: This may use {@code Context.current()} to pull the context associated with this
   * measurement.
   *
   * @param value The amount of the measurement.
   * @param attributes A set of attributes to associate with the count.
   */
  void record(double value, Attributes attributes);

  /**
   * Records a value with a set of attributes.
   *
   * @param value The amount of the measurement.
   * @param attributes A set of attributes to associate with the count.
   * @param context The explicit context to associate with this measurement.
   */
  void record(double value, Attributes attributes, Context context);
}
