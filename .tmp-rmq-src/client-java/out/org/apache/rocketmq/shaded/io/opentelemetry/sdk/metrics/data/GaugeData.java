/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data;

import javax.annotation.concurrent.Immutable;

/**
 * A gauge metric point.
 *
 * <p>See:
 * https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/datamodel.md#gauge
 */
@Immutable
public interface GaugeData<T extends PointData> extends Data<T> {}
