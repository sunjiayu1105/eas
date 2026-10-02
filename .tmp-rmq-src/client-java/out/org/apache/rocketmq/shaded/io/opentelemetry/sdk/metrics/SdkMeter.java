/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.api.internal.ValidationUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.LongCounterBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.Meter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.MeterProvider;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.export.CollectionInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.MeterProviderSharedState;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.MeterSharedState;
import java.util.Collection;

/** {@link SdkMeter} is SDK implementation of {@link Meter}. */
final class SdkMeter implements Meter {

  /**
   * Message appended to warnings when {@link ValidationUtil#checkValidInstrumentName(String,
   * String)} is {@code false}.
   */
  private static final String NOOP_INSTRUMENT_WARNING = " Returning noop instrument.";

  private static final Meter NOOP_METER = MeterProvider.noop().get("noop");
  private static final String NOOP_INSTRUMENT_NAME = "noop";

  private final InstrumentationScopeInfo instrumentationScopeInfo;
  private final MeterProviderSharedState meterProviderSharedState;
  private final MeterSharedState meterSharedState;

  SdkMeter(
      MeterProviderSharedState meterProviderSharedState,
      InstrumentationScopeInfo instrumentationScopeInfo) {
    this.instrumentationScopeInfo = instrumentationScopeInfo;
    this.meterProviderSharedState = meterProviderSharedState;
    this.meterSharedState = MeterSharedState.create(instrumentationScopeInfo);
  }

  // Visible for testing
  InstrumentationScopeInfo getInstrumentationScopeInfo() {
    return instrumentationScopeInfo;
  }

  /** Collects all the metric recordings that changed since the previous call. */
  Collection<MetricData> collectAll(
      CollectionInfo collectionInfo, long epochNanos, boolean suppressSynchronousCollection) {
    return meterSharedState.collectAll(
        collectionInfo, meterProviderSharedState, epochNanos, suppressSynchronousCollection);
  }

  @Override
  public LongCounterBuilder counterBuilder(String name) {
    return !ValidationUtil.checkValidInstrumentName(name, NOOP_INSTRUMENT_WARNING)
        ? NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME)
        : new SdkLongCounter.Builder(meterProviderSharedState, meterSharedState, name);
  }

  @Override
  public LongUpDownCounterBuilder upDownCounterBuilder(String name) {
    return !ValidationUtil.checkValidInstrumentName(name, NOOP_INSTRUMENT_WARNING)
        ? NOOP_METER.upDownCounterBuilder(NOOP_INSTRUMENT_NAME)
        : new SdkLongUpDownCounter.Builder(meterProviderSharedState, meterSharedState, name);
  }

  @Override
  public DoubleHistogramBuilder histogramBuilder(String name) {
    return !ValidationUtil.checkValidInstrumentName(name, NOOP_INSTRUMENT_WARNING)
        ? NOOP_METER.histogramBuilder(NOOP_INSTRUMENT_NAME)
        : new SdkDoubleHistogram.Builder(meterProviderSharedState, meterSharedState, name);
  }

  @Override
  public DoubleGaugeBuilder gaugeBuilder(String name) {
    return !ValidationUtil.checkValidInstrumentName(name, NOOP_INSTRUMENT_WARNING)
        ? NOOP_METER.gaugeBuilder(NOOP_INSTRUMENT_NAME)
        : new SdkDoubleGaugeBuilder(meterProviderSharedState, meterSharedState, name);
  }
}
