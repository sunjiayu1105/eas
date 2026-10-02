/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;

/**
 * Stores {@link MetricData} and allows synchronous writes of measurements.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public interface WriteableMetricStorage {
  /** Bind an efficient storage handle for a set of attributes. */
  BoundStorageHandle bind(Attributes attributes);

  /** Records a measurement. */
  default void recordLong(long value, Attributes attributes, Context context) {
    BoundStorageHandle handle = bind(attributes);
    try {
      handle.recordLong(value, attributes, context);
    } finally {
      handle.release();
    }
  }
  /** Records a measurement. */
  default void recordDouble(double value, Attributes attributes, Context context) {
    BoundStorageHandle handle = bind(attributes);
    try {
      handle.recordDouble(value, attributes, context);
    } finally {
      handle.release();
    }
  }
}
