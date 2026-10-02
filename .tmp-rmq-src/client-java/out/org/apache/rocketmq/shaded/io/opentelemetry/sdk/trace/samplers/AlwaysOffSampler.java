/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.samplers;

import org.apache.rocketmq.shaded.io.opentelemetry.api.common.Attributes;
import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.SpanKind;
import org.apache.rocketmq.shaded.io.opentelemetry.context.Context;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.data.LinkData;
import java.util.List;
import javax.annotation.concurrent.Immutable;

@Immutable
enum AlwaysOffSampler implements Sampler {
  INSTANCE;

  // Returns a "no" {@link SamplingResult} on {@link Span} sampling.
  @Override
  public SamplingResult shouldSample(
      Context parentContext,
      String traceId,
      String name,
      SpanKind spanKind,
      Attributes attributes,
      List<LinkData> parentLinks) {
    return ImmutableSamplingResult.EMPTY_NOT_SAMPLED_OR_RECORDED_SAMPLING_RESULT;
  }

  @Override
  public String getDescription() {
    return "AlwaysOffSampler";
  }

  @Override
  public String toString() {
    return getDescription();
  }
}
