/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.DoubleExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.ExemplarData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.LongExemplarData;
import java.util.Collections;
import java.util.List;

/** A reservoir that keeps no exemplars. */
class NoopExemplarReservoir<T extends ExemplarData> implements ExemplarReservoir<T> {

  static final NoopExemplarReservoir<LongExemplarData> LONG_INSTANCE =
      new NoopExemplarReservoir<>();
  static final NoopExemplarReservoir<DoubleExemplarData> DOUBLE_INSTANCE =
      new NoopExemplarReservoir<>();

  private NoopExemplarReservoir() {}

  @Override
  public void offerDoubleMeasurement(double value, Attributes attributes, Context context) {
    // Do nothing
  }

  @Override
  public void offerLongMeasurement(long value, Attributes attributes, Context context) {
    // Do nothing
  }

  @Override
  public List<T> collectAndReset(Attributes pointAttributes) {
    return Collections.emptyList();
  }
}
