/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace;

import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.Tracer;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.TracerBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.internal.ComponentRegistry;
import javax.annotation.Nullable;

class SdkTracerBuilder implements TracerBuilder {

  private final ComponentRegistry<SdkTracer> registry;
  private final String instrumentationScopeName;
  @Nullable private String instrumentationScopeVersion;
  @Nullable private String schemaUrl;

  SdkTracerBuilder(ComponentRegistry<SdkTracer> registry, String instrumentationScopeName) {
    this.registry = registry;
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public TracerBuilder setSchemaUrl(String schemaUrl) {
    this.schemaUrl = schemaUrl;
    return this;
  }

  @Override
  public TracerBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    this.instrumentationScopeVersion = instrumentationScopeVersion;
    return this;
  }

  @Override
  public Tracer build() {
    return registry.get(instrumentationScopeName, instrumentationScopeVersion, schemaUrl);
  }
}
