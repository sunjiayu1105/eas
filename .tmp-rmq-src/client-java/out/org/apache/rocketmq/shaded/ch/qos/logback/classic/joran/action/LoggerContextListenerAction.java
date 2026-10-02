/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package org.apache.rocketmq.shaded.ch.qos.logback.classic.joran.action;

import org.xml.sax.Attributes;

import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.LoggerContextListenerModel;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.spi.LoggerContextListener;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.action.BaseModelAction;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.action.PreconditionValidator;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.spi.SaxEventInterpretationContext;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model;

public class LoggerContextListenerAction extends BaseModelAction {
    boolean inError = false;
    LoggerContextListener lcl;

    @Override
    protected boolean validPreconditions(SaxEventInterpretationContext ic, String name, Attributes attributes) {
        PreconditionValidator pv = new PreconditionValidator(this, ic, name, attributes);
        pv.validateClassAttribute();
        return pv.isValid();
    }

    @Override
    protected Model buildCurrentModel(SaxEventInterpretationContext interpretationContext, String name,
            Attributes attributes) {
        LoggerContextListenerModel loggerContextListenerModel = new LoggerContextListenerModel();
        loggerContextListenerModel.setClassName(attributes.getValue(CLASS_ATTRIBUTE));
        return loggerContextListenerModel;
    }

}
