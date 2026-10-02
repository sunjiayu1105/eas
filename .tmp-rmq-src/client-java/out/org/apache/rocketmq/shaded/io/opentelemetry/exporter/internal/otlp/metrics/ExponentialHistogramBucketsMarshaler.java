/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.metrics;

import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.metrics.v1.internal.ExponentialHistogramDataPoint;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.internal.PrimitiveLongList;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.metrics.internal.data.exponentialhistogram.ExponentialHistogramBuckets;
import java.io.IOException;
import java.util.List;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public class ExponentialHistogramBucketsMarshaler extends MarshalerWithSize {
  private final int offset;
  private final List<Long> counts;

  static ExponentialHistogramBucketsMarshaler create(ExponentialHistogramBuckets buckets) {
    return new ExponentialHistogramBucketsMarshaler(buckets.getOffset(), buckets.getBucketCounts());
  }

  private ExponentialHistogramBucketsMarshaler(int offset, List<Long> counts) {
    super(calculateSize(offset, counts));
    this.offset = offset;
    this.counts = counts;
  }

  @Override
  protected void writeTo(Serializer output) throws IOException {
    output.serializeSInt32(ExponentialHistogramDataPoint.Buckets.OFFSET, offset);
    output.serializeRepeatedUInt64(
        ExponentialHistogramDataPoint.Buckets.BUCKET_COUNTS, PrimitiveLongList.toArray(counts));
  }

  private static int calculateSize(int offset, List<Long> counts) {
    int size = 0;
    size += MarshalerUtil.sizeSInt32(ExponentialHistogramDataPoint.Buckets.OFFSET, offset);
    size +=
        MarshalerUtil.sizeRepeatedUInt64(
            ExponentialHistogramDataPoint.Buckets.BUCKET_COUNTS, PrimitiveLongList.toArray(counts));
    return size;
  }
}
