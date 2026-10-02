/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.DoubleCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.DoubleCounterBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableDoubleCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.internal.ThrottlingLogger;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor.InstrumentDescriptor;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.instrument.BoundDoubleCounter;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.BoundStorageHandle;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.MeterProviderSharedState;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.MeterSharedState;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.state.WriteableMetricStorage;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SdkDoubleCounter extends AbstractInstrument implements DoubleCounter {
  private static final Logger logger = Logger.getLogger(SdkDoubleCounter.class.getName());

  private final ThrottlingLogger throttlingLogger = new ThrottlingLogger(logger);
  private final WriteableMetricStorage storage;

  private SdkDoubleCounter(InstrumentDescriptor descriptor, WriteableMetricStorage storage) {
    super(descriptor);
    this.storage = storage;
  }

  @Override
  public void add(double increment, Attributes attributes, Context context) {
    if (increment < 0) {
      throttlingLogger.log(
          Level.WARNING,
          "Counters can only increase. Instrument "
              + getDescriptor().getName()
              + " has recorded a negative value.");
      return;
    }
    storage.recordDouble(increment, attributes, context);
  }

  @Override
  public void add(double increment, Attributes attributes) {
    add(increment, attributes, Context.current());
  }

  @Override
  public void add(double increment) {
    add(increment, Attributes.empty());
  }

  BoundDoubleCounter bind(Attributes attributes) {
    return new BoundInstrument(getDescriptor(), storage.bind(attributes), attributes);
  }

  static final class BoundInstrument implements BoundDoubleCounter {
    private final ThrottlingLogger throttlingLogger = new ThrottlingLogger(logger);
    private final InstrumentDescriptor descriptor;
    private final BoundStorageHandle handle;
    private final Attributes attributes;

    BoundInstrument(
        InstrumentDescriptor descriptor, BoundStorageHandle handle, Attributes attributes) {
      this.descriptor = descriptor;
      this.handle = handle;
      this.attributes = attributes;
    }

    @Override
    public void add(double increment, Context context) {
      if (increment < 0) {
        throttlingLogger.log(
            Level.WARNING,
            "Counters can only increase. Instrument "
                + descriptor.getName()
                + " has recorded a negative value.");
        return;
      }
      handle.recordDouble(increment, attributes, context);
    }

    @Override
    public void add(double increment) {
      add(increment, Context.current());
    }

    @Override
    public void unbind() {
      handle.release();
    }
  }

  static final class Builder extends AbstractInstrumentBuilder<SdkDoubleCounter.Builder>
      implements DoubleCounterBuilder {

    Builder(
        MeterProviderSharedState meterProviderSharedState,
        MeterSharedState sharedState,
        String name,
        String description,
        String unit) {
      super(meterProviderSharedState, sharedState, name, description, unit);
    }

    @Override
    protected Builder getThis() {
      return this;
    }

    @Override
    public SdkDoubleCounter build() {
      return buildSynchronousInstrument(
          InstrumentType.COUNTER, InstrumentValueType.DOUBLE, SdkDoubleCounter::new);
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      return new SdkObservableInstrument(
          meterSharedState,
          registerDoubleAsynchronousInstrument(InstrumentType.OBSERVABLE_COUNTER, callback));
    }
  }
}
