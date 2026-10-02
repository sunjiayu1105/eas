/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableDoubleCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableDoubleGauge;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableDoubleUpDownCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableLongCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableLongGauge;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableLongUpDownCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.internal.ThrottlingLogger;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.CallbackRegistration;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.MeterSharedState;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

class SdkObservableInstrument
    implements ObservableDoubleCounter,
        ObservableLongCounter,
        ObservableDoubleGauge,
        ObservableLongGauge,
        ObservableDoubleUpDownCounter,
        ObservableLongUpDownCounter {

  private static final Logger logger = Logger.getLogger(SdkObservableInstrument.class.getName());

  private final ThrottlingLogger throttlingLogger = new ThrottlingLogger(logger);
  private final MeterSharedState meterSharedState;
  private final CallbackRegistration<?> callbackRegistration;
  private final AtomicBoolean removed = new AtomicBoolean(false);

  SdkObservableInstrument(
      MeterSharedState meterSharedState, CallbackRegistration<?> callbackRegistration) {
    this.meterSharedState = meterSharedState;
    this.callbackRegistration = callbackRegistration;
  }

  @Override
  public void close() {
    if (!removed.compareAndSet(false, true)) {
      throttlingLogger.log(
          Level.WARNING,
          "Instrument "
              + callbackRegistration.getInstrumentDescriptor().getName()
              + " has called close() multiple times.");
      return;
    }
    meterSharedState.removeCallback(callbackRegistration);
  }
}
