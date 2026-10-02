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
package org.apache.rocketmq.shaded.ch.qos.logback.core.hook;

import org.apache.rocketmq.shaded.ch.qos.logback.core.Context;
import org.apache.rocketmq.shaded.ch.qos.logback.core.ContextBase;
import org.apache.rocketmq.shaded.ch.qos.logback.core.CoreConstants;
import org.apache.rocketmq.shaded.ch.qos.logback.core.spi.ContextAwareBase;

/**
 * Base class for classes implementing a Logback ShutdownHook via extension
 *
 * @author Mike Reinhold
 */
public abstract class ShutdownHookBase extends ContextAwareBase implements ShutdownHook {

    public ShutdownHookBase() {
    }

    /**
     * Default method for stopping the Logback context
     */
    protected void stop() {
        addInfo("Logback context being closed via shutdown hook");

        Context hookContext = getContext();
        if (hookContext instanceof ContextBase) {
            ContextBase context = (ContextBase) hookContext;
            context.stop();
        }
    }

}
