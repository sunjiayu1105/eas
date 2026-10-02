package org.apache.rocketmq.shaded.ch.qos.logback.classic.model;

import org.apache.rocketmq.shaded.ch.qos.logback.core.model.NamedModel;

public class ContextNameModel extends NamedModel {

    private static final long serialVersionUID = -1635653921915985666L;

    @Override
    protected ContextNameModel makeNewInstance() {
        return new ContextNameModel();
    }
}
