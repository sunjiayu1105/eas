/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.export;

import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.SdkMeterProvider;

/**
 * A {@link CollectionRegistration} is passed to each {@link MetricReader} registered with {@link
 * SdkMeterProvider}, and provides readers the ability to trigger metric collections.
 */
// TODO(jack-berg): Have methods when custom MetricReaders are supported
public interface CollectionRegistration {}
