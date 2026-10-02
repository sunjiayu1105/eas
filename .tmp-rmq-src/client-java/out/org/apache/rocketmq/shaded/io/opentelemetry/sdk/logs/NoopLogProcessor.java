/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.logs;

import org.apache.rocketmq.shaded.io.opentelemetry.sdk.logs.data.LogData;

final class NoopLogProcessor implements LogProcessor {
  private static final NoopLogProcessor INSTANCE = new NoopLogProcessor();

  static LogProcessor getInstance() {
    return INSTANCE;
  }

  private NoopLogProcessor() {}

  @Override
  public void emit(LogData logData) {}
}
