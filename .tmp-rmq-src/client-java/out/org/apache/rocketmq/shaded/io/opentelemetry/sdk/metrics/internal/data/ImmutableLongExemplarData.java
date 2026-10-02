/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data;

import org.apache.rocketmq.shaded.com.google.auto.value.AutoValue;
import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.SpanContext;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.ExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.LongExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.PointData;
import javax.annotation.concurrent.Immutable;

/**
 * An {@link ExemplarData} with {@code long} measurements.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
@Immutable
@AutoValue
public abstract class ImmutableLongExemplarData implements LongExemplarData {

  /**
   * Construct a new exemplar.
   *
   * @param filteredAttributes The set of {@link Attributes} not already associated with the {@link
   *     PointData}.
   * @param recordTimeNanos The time when the sample qas recorded in nanoseconds.
   * @param spanContext The associated span context.
   * @param value The value recorded.
   */
  public static LongExemplarData create(
      Attributes filteredAttributes, long recordTimeNanos, SpanContext spanContext, long value) {
    return new AutoValue_ImmutableLongExemplarData(
        filteredAttributes, recordTimeNanos, spanContext, value);
  }

  ImmutableLongExemplarData() {}
}
