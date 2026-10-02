/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.exemplar;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;

/** Selects which {@link FixedSizeExemplarReservoir} {@link ReservoirCell} receives measurements. */
interface ReservoirCellSelector {

  /** Determine the index of the {@code cells} to record the measurement to. */
  int reservoirCellIndexFor(
      ReservoirCell[] cells, long value, Attributes attributes, Context context);

  /** Determine the index of the {@code cells} to record the measurement to. */
  int reservoirCellIndexFor(
      ReservoirCell[] cells, double value, Attributes attributes, Context context);

  /** Called when {@link FixedSizeExemplarReservoir#collectAndReset(Attributes)}. */
  void reset();
}
