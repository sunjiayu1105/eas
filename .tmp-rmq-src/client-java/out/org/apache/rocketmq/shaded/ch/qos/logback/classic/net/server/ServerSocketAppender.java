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
package org.apache.rocketmq.shaded.ch.qos.logback.classic.net.server;

import org.apache.rocketmq.shaded.ch.qos.logback.classic.net.LoggingEventPreSerializationTransformer;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.spi.ILoggingEvent;
import org.apache.rocketmq.shaded.ch.qos.logback.core.net.server.AbstractServerSocketAppender;
import org.apache.rocketmq.shaded.ch.qos.logback.core.spi.PreSerializationTransformer;

/**
 * An appender that listens on a TCP port for connections from remote loggers.
 * Each event delivered to this appender is delivered to all connected remote
 * loggers.
 *
 * @author Carl Harris
 */
public class ServerSocketAppender extends AbstractServerSocketAppender<ILoggingEvent> {

    private static final PreSerializationTransformer<ILoggingEvent> pst = new LoggingEventPreSerializationTransformer();

    private boolean includeCallerData;

    @Override
    protected void postProcessEvent(ILoggingEvent event) {
        if (isIncludeCallerData()) {
            event.getCallerData();
        }
    }

    @Override
    protected PreSerializationTransformer<ILoggingEvent> getPST() {
        return pst;
    }

    public boolean isIncludeCallerData() {
        return includeCallerData;
    }

    public void setIncludeCallerData(boolean includeCallerData) {
        this.includeCallerData = includeCallerData;
    }

}
