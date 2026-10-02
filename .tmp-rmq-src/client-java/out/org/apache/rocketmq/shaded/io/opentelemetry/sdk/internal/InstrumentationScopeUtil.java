/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.internal;

import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class InstrumentationScopeUtil {

  /** Convert to {@link org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationLibraryInfo}. */
  @SuppressWarnings("deprecation") // Utility method for compatibility
  public static org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationLibraryInfo toInstrumentationLibraryInfo(
      InstrumentationScopeInfo instrumentationScopeInfo) {
    return org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create(
        instrumentationScopeInfo.getName(),
        instrumentationScopeInfo.getVersion(),
        instrumentationScopeInfo.getSchemaUrl());
  }

  /** Convert to {@link InstrumentationScopeInfo}. */
  @SuppressWarnings("deprecation") // Utility method for compatibility
  public static InstrumentationScopeInfo toInstrumentationScopeInfo(
      org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationLibraryInfo instrumentationLibraryInfo) {
    return InstrumentationScopeInfo.create(
        instrumentationLibraryInfo.getName(),
        instrumentationLibraryInfo.getVersion(),
        instrumentationLibraryInfo.getSchemaUrl());
  }

  private InstrumentationScopeUtil() {}
}
