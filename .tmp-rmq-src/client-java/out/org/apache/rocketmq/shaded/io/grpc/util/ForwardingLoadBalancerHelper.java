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
import org.apache.rocketmq.shaded.io.grpc.ChannelCredentials;
import org.apache.rocketmq.shaded.io.grpc.ChannelLogger;
import org.apache.rocketmq.shaded.io.grpc.ConnectivityState;
import org.apache.rocketmq.shaded.io.grpc.EquivalentAddressGroup;
import org.apache.rocketmq.shaded.io.grpc.ExperimentalApi;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer.CreateSubchannelArgs;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer.Subchannel;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer.SubchannelPicker;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer;
import org.apache.rocketmq.shaded.io.grpc.ManagedChannel;
import org.apache.rocketmq.shaded.io.grpc.ManagedChannelBuilder;
import org.apache.rocketmq.shaded.io.grpc.NameResolver;
import org.apache.rocketmq.shaded.io.grpc.NameResolverRegistry;
import org.apache.rocketmq.shaded.io.grpc.SynchronizationContext;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public abstract class ForwardingLoadBalancerHelper extends LoadBalancer.Helper {
  /**
   * Returns the underlying helper.
   */
  protected abstract LoadBalancer.Helper delegate();

  @Override
  public Subchannel createSubchannel(CreateSubchannelArgs args) {
    return delegate().createSubchannel(args);
  }

  @Override
  public  ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
    return delegate().createOobChannel(eag, authority);
  }

  @Override
  public  ManagedChannel createOobChannel(List<EquivalentAddressGroup> eag, String authority) {
    return delegate().createOobChannel(eag, authority);
  }

  @Override
  public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
    delegate().updateOobChannelAddresses(channel, eag);
  }

  @Override
  public void updateOobChannelAddresses(ManagedChannel channel, List<EquivalentAddressGroup> eag) {
    delegate().updateOobChannelAddresses(channel, eag);
  }

  @Deprecated
  @Override
  public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target) {
    return delegate().createResolvingOobChannelBuilder(target);
  }

  @Override
  public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(
      String target, ChannelCredentials creds) {
    return delegate().createResolvingOobChannelBuilder(target, creds);
  }

  @Override
  public ManagedChannel createResolvingOobChannel(String target) {
    return delegate().createResolvingOobChannel(target);
  }

  @Override
  public void updateBalancingState(
      ConnectivityState newState, SubchannelPicker newPicker) {
    delegate().updateBalancingState(newState, newPicker);
  }

  @Override
  public void refreshNameResolution() {
    delegate().refreshNameResolution();
  }

  @Override
  @Deprecated
  public void ignoreRefreshNameResolutionCheck() {
    delegate().ignoreRefreshNameResolutionCheck();
  }

  @Override
  public String getAuthority() {
    return delegate().getAuthority();
  }

  @Override
  public ChannelCredentials getChannelCredentials() {
    return delegate().getChannelCredentials();
  }

  @Override
  public ChannelCredentials getUnsafeChannelCredentials() {
    return delegate().getUnsafeChannelCredentials();
  }

  @Override
  public SynchronizationContext getSynchronizationContext() {
    return delegate().getSynchronizationContext();
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return delegate().getScheduledExecutorService();
  }

  @Override
  public ChannelLogger getChannelLogger() {
    return delegate().getChannelLogger();
  }

  @Override
  public NameResolver.Args getNameResolverArgs() {
    return delegate().getNameResolverArgs();
  }

  @Override
  public NameResolverRegistry getNameResolverRegistry() {
    return delegate().getNameResolverRegistry();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
  }
}
