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

import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.ContextNameModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.action.BaseModelAction;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.spi.SaxEventInterpretationContext;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model;

public class ContextNameAction extends BaseModelAction {

    @Override
    protected Model buildCurrentModel(SaxEventInterpretationContext interpretationContext, String name,
            Attributes attributes) {
        ContextNameModel contextNameModel = new ContextNameModel();
        return contextNameModel;
    }

}
