/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.descriptor;

import org.apache.rocketmq.shaded.com.google.auto.value.AutoValue;
import org.apache.rocketmq.shaded.com.google.auto.value.extension.memoized.Memoized;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.InstrumentType;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.InstrumentValueType;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.debug.SourceInfo;
import javax.annotation.concurrent.Immutable;

/**
 * Describes an instrument that was registered to record data.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
@AutoValue
@Immutable
public abstract class InstrumentDescriptor {

  private final SourceInfo sourceInfo = SourceInfo.fromCurrentStack();

  public static InstrumentDescriptor create(
      String name,
      String description,
      String unit,
      InstrumentType type,
      InstrumentValueType valueType) {
    return new AutoValue_InstrumentDescriptor(name, description, unit, type, valueType);
  }

  InstrumentDescriptor() {}

  public abstract String getName();

  public abstract String getDescription();

  public abstract String getUnit();

  public abstract InstrumentType getType();

  public abstract InstrumentValueType getValueType();

  /**
   * Debugging information for this instrument. Ignored from {@link #equals(Object)} and {@link
   * #toString()}.
   */
  public final SourceInfo getSourceInfo() {
    return sourceInfo;
  }

  @Memoized
  @Override
  public abstract int hashCode();
}
