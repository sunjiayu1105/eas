/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.LongGaugeBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableLongGauge;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableLongMeasurement;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.MeterProviderSharedState;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.MeterSharedState;
import java.util.function.Consumer;

final class SdkLongGaugeBuilder extends AbstractInstrumentBuilder<SdkLongGaugeBuilder>
    implements LongGaugeBuilder {

  SdkLongGaugeBuilder(
      MeterProviderSharedState meterProviderSharedState,
      MeterSharedState sharedState,
      String name,
      String description,
      String unit) {
    super(meterProviderSharedState, sharedState, name, description, unit);
  }

  @Override
  protected SdkLongGaugeBuilder getThis() {
    return this;
  }

  @Override
  public ObservableLongGauge buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
    return new SdkObservableInstrument(
        meterSharedState,
        registerLongAsynchronousInstrument(InstrumentType.OBSERVABLE_GAUGE, callback));
  }
}
