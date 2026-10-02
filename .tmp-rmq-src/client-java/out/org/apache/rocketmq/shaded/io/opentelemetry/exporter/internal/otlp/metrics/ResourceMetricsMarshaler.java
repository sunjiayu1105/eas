/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Marshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.InstrumentationScopeMarshaller;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.ResourceMarshaler;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.ResourceMetrics;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A Marshaler of ResourceMetrics.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ResourceMetricsMarshaler extends MarshalerWithSize {
  private final ResourceMarshaler resourceMarshaler;
  private final byte[] schemaUrl;
  private final InstrumentationScopeMetricsMarshaler[] instrumentationScopeMetricsMarshalers;

  /** Returns Marshalers of ResourceMetrics created by grouping the provided metricData. */
  public static ResourceMetricsMarshaler[] create(Collection<MetricData> metricDataList) {
    Map<Resource, Map<InstrumentationScopeInfo, List<Marshaler>>> resourceAndScopeMap =
        groupByResourceAndScope(metricDataList);

    ResourceMetricsMarshaler[] resourceMetricsMarshalers =
        new ResourceMetricsMarshaler[resourceAndScopeMap.size()];
    int posResource = 0;
    for (Map.Entry<Resource, Map<InstrumentationScopeInfo, List<Marshaler>>> entry :
        resourceAndScopeMap.entrySet()) {
      InstrumentationScopeMetricsMarshaler[] instrumentationLibrarySpansMarshalers =
          new InstrumentationScopeMetricsMarshaler[entry.getValue().size()];
      int posInstrumentation = 0;
      for (Map.Entry<InstrumentationScopeInfo, List<Marshaler>> entryIs :
          entry.getValue().entrySet()) {
        instrumentationLibrarySpansMarshalers[posInstrumentation++] =
            new InstrumentationScopeMetricsMarshaler(
                InstrumentationScopeMarshaller.create(entryIs.getKey()),
                MarshalerUtil.toBytes(entryIs.getKey().getSchemaUrl()),
                entryIs.getValue());
      }
      resourceMetricsMarshalers[posResource++] =
          new ResourceMetricsMarshaler(
              ResourceMarshaler.create(entry.getKey()),
              MarshalerUtil.toBytes(entry.getKey().getSchemaUrl()),
              instrumentationLibrarySpansMarshalers);
    }

    return resourceMetricsMarshalers;
  }

  ResourceMetricsMarshaler(
      ResourceMarshaler resourceMarshaler,
      byte[] schemaUrl,
      InstrumentationScopeMetricsMarshaler[] instrumentationScopeMetricsMarshalers) {
    super(calculateSize(resourceMarshaler, schemaUrl, instrumentationScopeMetricsMarshalers));
    this.resourceMarshaler = resourceMarshaler;
    this.schemaUrl = schemaUrl;
    this.instrumentationScopeMetricsMarshalers = instrumentationScopeMetricsMarshalers;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeMessage(ResourceMetrics.RESOURCE, resourceMarshaler);
    output.serializeRepeatedMessage(
        ResourceMetrics.SCOPE_METRICS, instrumentationScopeMetricsMarshalers);
    output.serializeString(ResourceMetrics.SCHEMA_URL, schemaUrl);
  }

  private static int calculateSize(
      ResourceMarshaler resourceMarshaler,
      byte[] schemaUrl,
      InstrumentationScopeMetricsMarshaler[] instrumentationScopeMetricsMarshalers) {
    int size = 0;
    size += MarshalerUtil.sizeMessage(ResourceMetrics.RESOURCE, resourceMarshaler);
    size += MarshalerUtil.sizeBytes(ResourceMetrics.SCHEMA_URL, schemaUrl);
    size +=
        MarshalerUtil.sizeRepeatedMessage(
            ResourceMetrics.SCOPE_METRICS, instrumentationScopeMetricsMarshalers);
    return size;
  }

  private static Map<Resource, Map<InstrumentationScopeInfo, List<Marshaler>>>
      groupByResourceAndScope(Collection<MetricData> metricDataList) {
    return MarshalerUtil.groupByResourceAndScope(
        metricDataList,
        // TODO(anuraaga): Replace with an internal SdkData type of interface that exposes these
        // two.
        MetricData::getResource,
        MetricData::getInstrumentationScopeInfo,
        MetricMarshaler::create);
  }
}
