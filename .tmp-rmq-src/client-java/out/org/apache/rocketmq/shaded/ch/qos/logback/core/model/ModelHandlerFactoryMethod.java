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
package org.apache.rocketmq.shaded.ch.qos.logback.core.model;

import org.apache.rocketmq.shaded.ch.qos.logback.core.Context;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelHandlerBase;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelInterpretationContext;

@FunctionalInterface
public interface ModelHandlerFactoryMethod {

    public ModelHandlerBase make(Context context, ModelInterpretationContext ic);
}
