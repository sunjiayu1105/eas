/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.logs;

import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.logs.data.LogDataBuilder;

/** SDK implementation of {@link LogEmitter}. */
final class SdkLogEmitter implements LogEmitter {

  private final LogEmitterSharedState logEmitterSharedState;
  private final InstrumentationScopeInfo instrumentationScopeInfo;

  SdkLogEmitter(
      LogEmitterSharedState logEmitterSharedState,
      InstrumentationScopeInfo instrumentationScopeInfo) {
    this.logEmitterSharedState = logEmitterSharedState;
    this.instrumentationScopeInfo = instrumentationScopeInfo;
  }

  @Override
  public LogBuilder logBuilder() {
    LogDataBuilder logDataBuilder =
        LogDataBuilder.create(
            logEmitterSharedState.getResource(),
            instrumentationScopeInfo,
            logEmitterSharedState.getClock());
    return new SdkLogBuilder(logEmitterSharedState, logDataBuilder);
  }

  // VisibleForTesting
  InstrumentationScopeInfo getInstrumentationScopeInfo() {
    return instrumentationScopeInfo;
  }
}
