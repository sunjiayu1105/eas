/*
 * Copyright 2018 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.shaded.io.grpc.util;

import org.apache.rocketmq.shaded.com.google.common.base.MoreObjects;
import org.apache.rocketmq.shaded.io.grpc.ConnectivityStateInfo;
import org.apache.rocketmq.shaded.io.grpc.ExperimentalApi;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer;
import org.apache.rocketmq.shaded.io.grpc.Status;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public abstract class ForwardingLoadBalancer extends LoadBalancer {
  /**
   * Returns the underlying balancer.
   */
  protected abstract LoadBalancer delegate();

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    delegate().handleResolvedAddresses(resolvedAddresses);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    delegate().handleNameResolutionError(error);
  }

  @Deprecated
  @Override
  public void handleSubchannelState(
      Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    delegate().handleSubchannelState(subchannel, stateInfo);
  }

  @Override
  public void shutdown() {
    delegate().shutdown();
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return delegate().canHandleEmptyAddressListFromNameResolution();
  }

  @Override
  public void requestConnection() {
    delegate().requestConnection();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
  }
}
