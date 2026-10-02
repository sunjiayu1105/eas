/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2022, QOS.ch. All rights reserved.
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

package org.apache.rocketmq.shaded.ch.qos.logback.core.joran.conditional;

import org.xml.sax.Attributes;

import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.action.BaseModelAction;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.action.PreconditionValidator;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.spi.SaxEventInterpretationContext;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.conditional.IfModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model;

public class IfAction extends BaseModelAction  {
    
    public static final String CONDITION_ATTRIBUTE = "condition"; 
    
    @Override
    protected boolean validPreconditions(SaxEventInterpretationContext interpcont, String name, Attributes attributes) {
        PreconditionValidator pv = new PreconditionValidator(this, interpcont, name, attributes);
        pv.generic(CONDITION_ATTRIBUTE);
        return pv.isValid();
    }
    
    @Override
    protected Model buildCurrentModel(SaxEventInterpretationContext interpretationContext, String name,
            Attributes attributes) {
        IfModel ifModel = new IfModel();
        
        String condition = attributes.getValue(CONDITION_ATTRIBUTE);
        ifModel.setCondition(condition);
        
        return ifModel;
    }

}
