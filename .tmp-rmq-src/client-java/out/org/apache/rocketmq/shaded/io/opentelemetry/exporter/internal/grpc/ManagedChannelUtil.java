/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.grpc;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import org.apache.rocketmq.shaded.io.grpc.ManagedChannel;
import org.apache.rocketmq.shaded.io.grpc.ManagedChannelBuilder;
import org.apache.rocketmq.shaded.io.grpc.netty.GrpcSslContexts;
import org.apache.rocketmq.shaded.io.grpc.netty.NettyChannelBuilder;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.TlsUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.retry.RetryPolicy;
import org.apache.rocketmq.shaded.io.opentelemetry.exporter.internal.retry.RetryUtil;
import org.apache.rocketmq.shaded.io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Utilities for working with gRPC channels.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ManagedChannelUtil {

  private static final Logger logger = Logger.getLogger(ManagedChannelUtil.class.getName());

  /**
   * Configure the channel builder to trust the certificates. The {@code byte[]} should contain an
   * X.509 certificate collection in PEM format.
   *
   * @throws SSLException if error occur processing the certificates
   */
  public static void setClientKeysAndTrustedCertificatesPem(
      ManagedChannelBuilder<?> managedChannelBuilder,
      @Nullable byte[] privateKeyPem,
      @Nullable byte[] certificatePem,
      byte[] trustedCertificatesPem)
      throws SSLException {
    requireNonNull(managedChannelBuilder, "managedChannelBuilder");
    requireNonNull(trustedCertificatesPem, "trustedCertificatesPem");

    X509TrustManager tmf = TlsUtil.trustManager(trustedCertificatesPem);
    X509KeyManager kmf = null;
    if (privateKeyPem != null && certificatePem != null) {
      kmf = TlsUtil.keyManager(privateKeyPem, certificatePem);
    }

    // gRPC does not abstract TLS configuration so we need to check the implementation and act
    // accordingly.
    if (managedChannelBuilder.getClass().getName().equals("org.apache.rocketmq.shaded.io.grpc.netty.NettyChannelBuilder")) {
      NettyChannelBuilder nettyBuilder = (NettyChannelBuilder) managedChannelBuilder;
      nettyBuilder.sslContext(
          GrpcSslContexts.forClient().keyManager(kmf).trustManager(tmf).build());
    } else if (managedChannelBuilder
        .getClass()
        .getName()
        .equals("org.apache.rocketmq.shaded.grpc.netty.NettyChannelBuilder")) {
      org.apache.rocketmq.shaded.grpc.netty.NettyChannelBuilder nettyBuilder =
          (org.apache.rocketmq.shaded.grpc.netty.NettyChannelBuilder) managedChannelBuilder;
      nettyBuilder.sslContext(
          org.apache.rocketmq.shaded.grpc.netty.GrpcSslContexts.forClient()
              .trustManager(tmf)
              .keyManager(kmf)
              .build());
    } else if (managedChannelBuilder
        .getClass()
        .getName()
        .equals("org.apache.rocketmq.shaded.io.grpc.okhttp.OkHttpChannelBuilder")) {
      org.apache.rocketmq.shaded.io.grpc.okhttp.OkHttpChannelBuilder okHttpBuilder =
          (org.apache.rocketmq.shaded.io.grpc.okhttp.OkHttpChannelBuilder) managedChannelBuilder;
      okHttpBuilder.sslSocketFactory(TlsUtil.sslSocketFactory(kmf, tmf));
    } else {
      throw new SSLException(
          "TLS certificate configuration not supported for unrecognized ManagedChannelBuilder "
              + managedChannelBuilder.getClass().getName());
    }
  }

  /**
   * Convert the {@link RetryPolicy} into a gRPC service config for the {@code serviceName}. The
   * resulting map can be passed to {@link ManagedChannelBuilder#defaultServiceConfig(Map)}.
   */
  public static Map<String, ?> toServiceConfig(String serviceName, RetryPolicy retryPolicy) {
    List<Double> retryableStatusCodes =
        RetryUtil.retryableGrpcStatusCodes().stream().map(Double::parseDouble).collect(toList());

    Map<String, Object> retryConfig = new HashMap<>();
    retryConfig.put("retryableStatusCodes", retryableStatusCodes);
    retryConfig.put("maxAttempts", (double) retryPolicy.getMaxAttempts());
    retryConfig.put("initialBackoff", retryPolicy.getInitialBackoff().toMillis() / 1000.0 + "s");
    retryConfig.put("maxBackoff", retryPolicy.getMaxBackoff().toMillis() / 1000.0 + "s");
    retryConfig.put("backoffMultiplier", retryPolicy.getBackoffMultiplier());

    Map<String, Object> methodConfig = new HashMap<>();
    methodConfig.put(
        "name", Collections.singletonList(Collections.singletonMap("service", serviceName)));
    methodConfig.put("retryPolicy", retryConfig);

    return Collections.singletonMap("methodConfig", Collections.singletonList(methodConfig));
  }

  /** Shutdown the gRPC channel. */
  public static CompletableResultCode shutdownChannel(ManagedChannel managedChannel) {
    CompletableResultCode result = new CompletableResultCode();
    managedChannel.shutdown();
    // Remove thread creation if gRPC adds an asynchronous shutdown API.
    // https://github.com/grpc/grpc-java/issues/8432
    Thread thread =
        new Thread(
            () -> {
              try {
                managedChannel.awaitTermination(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Level.WARNING, "Failed to shutdown the gRPC channel", e);
                result.fail();
              }
              result.succeed();
            });
    thread.setDaemon(true);
    thread.setName("grpc-cleanup");
    thread.start();
    return result;
  }

  private ManagedChannelUtil() {}
}
