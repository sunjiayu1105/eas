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
package org.apache.rocketmq.shaded.ch.qos.logback.core.joran.action;

import org.xml.sax.Attributes;

import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.spi.ActionException;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.spi.SaxEventInterpretationContext;

/**
 * @author Ceki Gulcu
 */
public class ContextPropertyAction extends Action {

    @Override
    public void begin(SaxEventInterpretationContext ec, String name, Attributes attributes) throws ActionException {
        addError("The [contextProperty] element has been removed. Please use [property] element instead");
    }

    @Override
    public void end(SaxEventInterpretationContext ec, String name) throws ActionException {
    }

}
