/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.logs.data;

import org.apache.rocketmq.shaded.com.google.auto.value.AutoValue;
import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.SpanContext;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.resources.Resource;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@AutoValue
@AutoValue.CopyAnnotations
@Immutable
abstract class LogDataImpl implements LogData {

  LogDataImpl() {}

  static LogDataImpl create(
      Resource resource,
      InstrumentationScopeInfo instrumentationScopeInfo,
      long epochNanos,
      SpanContext spanContext,
      Severity severity,
      @Nullable String severityText,
      Body body,
      Attributes attributes) {
    return new AutoValue_LogDataImpl(
        resource,
        instrumentationScopeInfo,
        epochNanos,
        spanContext,
        severity,
        severityText,
        body,
        attributes);
  }
}
