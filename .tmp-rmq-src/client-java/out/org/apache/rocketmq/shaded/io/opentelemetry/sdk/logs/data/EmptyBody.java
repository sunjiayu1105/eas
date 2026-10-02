/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.logs.data;

enum EmptyBody implements Body {
  INSTANCE;

  @Override
  public String asString() {
    return "";
  }

  @Override
  public Type getType() {
    return Type.EMPTY;
  }
}
