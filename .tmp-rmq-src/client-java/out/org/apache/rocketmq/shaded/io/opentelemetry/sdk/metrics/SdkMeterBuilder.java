/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.Meter;
import org.apache.rocketmq.shaded.io.opentelemetry.api.metrics.MeterBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.internal.ComponentRegistry;
import javax.annotation.Nullable;

class SdkMeterBuilder implements MeterBuilder {

  private final ComponentRegistry<SdkMeter> registry;
  private final String instrumentationScopeName;
  @Nullable private String instrumentationScopeVersion;
  @Nullable private String schemaUrl;

  SdkMeterBuilder(ComponentRegistry<SdkMeter> registry, String instrumentationScopeName) {
    this.registry = registry;
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public MeterBuilder setSchemaUrl(String schemaUrl) {
    this.schemaUrl = schemaUrl;
    return this;
  }

  @Override
  public MeterBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    this.instrumentationScopeVersion = instrumentationScopeVersion;
    return this;
  }

  @Override
  public Meter build() {
    return registry.get(instrumentationScopeName, instrumentationScopeVersion, schemaUrl);
  }
}
