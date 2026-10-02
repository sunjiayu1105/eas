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
package org.apache.rocketmq.shaded.ch.qos.logback.core.joran.action;

import java.util.Stack;

import org.xml.sax.Attributes;

import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.spi.ActionException;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.spi.SaxEventInterpretationContext;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.ImplicitModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model;
import org.apache.rocketmq.shaded.ch.qos.logback.core.spi.ErrorCodes;

/**
 * 
 * Action dealing with elements corresponding to implicit rules.
 * 
 * 
 * @author Ceki G&uuml;lc&uuml;
 *
 */
// TODO: rename to DefaultImplicitRuleAction (after Model migration)
public class ImplicitModelAction extends Action {

    Stack<ImplicitModel> currentImplicitModelStack = new Stack<>();

    @Override
    public void begin(SaxEventInterpretationContext interpretationContext, String name, Attributes attributes)
            throws ActionException {
        ImplicitModel currentImplicitModel = new ImplicitModel();
        currentImplicitModel.setTag(name);

        String className = attributes.getValue(CLASS_ATTRIBUTE);
        currentImplicitModel.setClassName(className);
        currentImplicitModelStack.push(currentImplicitModel);
        interpretationContext.pushModel(currentImplicitModel);
    }

    @Override
    public void body(SaxEventInterpretationContext ec, String body) {
        ImplicitModel implicitModel = currentImplicitModelStack.peek();
        implicitModel.addText(body);
    }

    @Override
    public void end(SaxEventInterpretationContext interpretationContext, String name) throws ActionException {

        ImplicitModel implicitModel = currentImplicitModelStack.peek();
        Model otherImplicitModel = interpretationContext.popModel();

        if (implicitModel != otherImplicitModel) {
            addError(implicitModel + " does not match " + otherImplicitModel);
            return;
        }
        Model parentModel = interpretationContext.peekModel();
        if(parentModel != null) {
            parentModel.addSubModel(implicitModel);
        } else {
            addWarn(ErrorCodes.PARENT_MODEL_NOT_FOUND);      
            addWarn(ErrorCodes.SKIPPING_IMPLICIT_MODEL_ADDITION);
        }
        currentImplicitModelStack.pop();

    }

}
