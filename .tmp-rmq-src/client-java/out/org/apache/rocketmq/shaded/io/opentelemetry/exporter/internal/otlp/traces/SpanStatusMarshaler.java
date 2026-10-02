/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.otlp.traces;

import org.apache.rocketmq.shaded.io.opentelemetry.api.trace.StatusCode;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.ProtoEnumInfo;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.marshal.Serializer;
import org.apache.rocketmq.shaded.io.opentelemetry.proto.trace.v1.internal.Status;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.trace.data.StatusData;
import java.io.IOException;

final class SpanStatusMarshaler extends MarshalerWithSize {
  private final ProtoEnumInfo protoStatusCode;
  private final byte[] descriptionUtf8;

  static SpanStatusMarshaler create(StatusData status) {
    ProtoEnumInfo protoStatusCode = Status.StatusCode.STATUS_CODE_UNSET;
    if (status.getStatusCode() == StatusCode.OK) {
      protoStatusCode = Status.StatusCode.STATUS_CODE_OK;
    } else if (status.getStatusCode() == StatusCode.ERROR) {
      protoStatusCode = Status.StatusCode.STATUS_CODE_ERROR;
    }
    byte[] description = MarshalerUtil.toBytes(status.getDescription());
    return new SpanStatusMarshaler(protoStatusCode, description);
  }

  private SpanStatusMarshaler(ProtoEnumInfo protoStatusCode, byte[] descriptionUtf8) {
    super(computeSize(protoStatusCode, descriptionUtf8));
    this.protoStatusCode = protoStatusCode;
    this.descriptionUtf8 = descriptionUtf8;
  }

  @Override
  public void writeTo(Serializer output) throws IOException {
    output.serializeString(Status.MESSAGE, descriptionUtf8);
    output.serializeEnum(Status.CODE, protoStatusCode);
  }

  private static int computeSize(ProtoEnumInfo protoStatusCode, byte[] descriptionUtf8) {
    int size = 0;
    size += MarshalerUtil.sizeBytes(Status.MESSAGE, descriptionUtf8);
    size += MarshalerUtil.sizeEnum(Status.CODE, protoStatusCode);
    return size;
  }
}
