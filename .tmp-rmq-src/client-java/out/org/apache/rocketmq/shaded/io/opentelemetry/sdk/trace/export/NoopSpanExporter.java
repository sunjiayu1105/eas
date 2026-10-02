/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.export;

import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.CompletableResultCode;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Collection;

final class NoopSpanExporter implements SpanExporter {

  private static final SpanExporter INSTANCE = new NoopSpanExporter();

  static SpanExporter getInstance() {
    return INSTANCE;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public String toString() {
    return "NoopSpanExporter{}";
  }
}
