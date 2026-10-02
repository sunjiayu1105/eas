/*
 * Copyright 2019 The gRPC Authors
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
import org.apache.rocketmq.shaded.io.grpc.Attributes;
import org.apache.rocketmq.shaded.io.grpc.Channel;
import org.apache.rocketmq.shaded.io.grpc.ChannelLogger;
import org.apache.rocketmq.shaded.io.grpc.EquivalentAddressGroup;
import org.apache.rocketmq.shaded.io.grpc.ExperimentalApi;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer.Subchannel;
import org.apache.rocketmq.shaded.io.grpc.LoadBalancer.SubchannelStateListener;
import java.util.List;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public abstract class ForwardingSubchannel extends LoadBalancer.Subchannel {
  /**
   * Returns the underlying Subchannel.
   */
  protected abstract Subchannel delegate();

  @Override
  public void start(SubchannelStateListener listener) {
    delegate().start(listener);
  }

  @Override
  public void shutdown() {
    delegate().shutdown();
  }

  @Override
  public void requestConnection() {
    delegate().requestConnection();
  }

  @Override
  public List<EquivalentAddressGroup> getAllAddresses() {
    return delegate().getAllAddresses();
  }

  @Override
  public Attributes getAttributes() {
    return delegate().getAttributes();
  }

  @Override
  public Channel asChannel() {
    return delegate().asChannel();
  }

  @Override
  public ChannelLogger getChannelLogger() {
    return delegate().getChannelLogger();
  }

  @Override
  public Object getInternalSubchannel() {
    return delegate().getInternalSubchannel();
  }

  @Override
  public void updateAddresses(List<EquivalentAddressGroup> addrs) {
    delegate().updateAddresses(addrs);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
  }
}
