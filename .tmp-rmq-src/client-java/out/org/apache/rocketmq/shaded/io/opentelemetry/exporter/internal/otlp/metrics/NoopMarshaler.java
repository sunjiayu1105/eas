/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;

final class NoopMarshaler extends MarshalerWithSize {

  static final NoopMarshaler INSTANCE = new NoopMarshaler();

  private NoopMarshaler() {
    super(0);
  }

  @Override
  public void writeTo(Serializer output) {}
}
