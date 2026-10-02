package org.apache.rocketmq.shaded.ch.qos.logback.core.property;

import org.apache.rocketmq.shaded.ch.qos.logback.core.PropertyDefinerBase;
import org.apache.rocketmq.shaded.ch.qos.logback.core.util.NetworkAddressUtil;

public class CanonicalHostNamePropertyDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return new NetworkAddressUtil(getContext()).safelyGetCanonicalLocalHostName();
    }

}
