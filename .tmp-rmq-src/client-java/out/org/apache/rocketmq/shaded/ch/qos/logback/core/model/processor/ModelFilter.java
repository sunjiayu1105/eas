package org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor;

import org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model;
import org.apache.rocketmq.shaded.ch.qos.logback.core.spi.FilterReply;

public interface ModelFilter {

    FilterReply decide(Model model);

}
