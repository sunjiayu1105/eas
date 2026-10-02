/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Utilities that allow tracing services to export data for sampled spans, as well as providing
 * in-process span processing APIs.
 *
 * <h2>Contents</h2>
 *
 * <ul>
 *   <li>{@link org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.export.SpanExporter}
 *   <li>{@link org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.export.SimpleSpanProcessor}
 *   <li>{@link org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.export.BatchSpanProcessor}
 *   <li>{@link org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder}
 * </ul>
 *
 * <p>Configuration options for components in this package can be read from system properties or
 * environment variables with the use of the opentelemetry-autoconfiguration module.
 */
@ParametersAreNonnullByDefault
package org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.export;

import javax.annotation.ParametersAreNonnullByDefault;
