/*
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2023, QOS.ch. All rights reserved.
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

package org.apache.rocketmq.shaded.ch.qos.logback.classic.joran;

import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.ConfigurationModel;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.ContextNameModel;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.LevelModel;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.LoggerContextListenerModel;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.LoggerModel;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.RootLoggerModel;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.processor.ConfigurationModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.processor.ContextNameModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.processor.LevelModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.processor.LoggerContextListenerModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.processor.LoggerModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.processor.RootLoggerModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.core.Context;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.ModelClassToModelHandlerLinkerBase;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.AppenderModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.AppenderRefModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.InsertFromJNDIModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.ModelHandlerFactoryMethod;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.AppenderModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.AppenderRefDependencyAnalyser;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.AppenderRefModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.DefaultProcessor;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.InsertFromJNDIModelHandler;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.RefContainerDependencyAnalyser;

/**
 * For a given DefaultProcessor instance link a {@link org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model Model} class to a
 * {@link org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelHandlerBase ModelHandler} instance for
 * logback-classic.
 *
 * <p>Will also use links from super class.</p>
 *
 * @since 1.3.9/1.4.9
 */
public class ModelClassToModelHandlerLinker extends ModelClassToModelHandlerLinkerBase {

    public ModelClassToModelHandlerLinker(Context context) {
        super(context);
    }

    ModelHandlerFactoryMethod configurationModelHandlerFactoryMethod;

    @Override
    public void link(DefaultProcessor defaultProcessor) {
        super.link(defaultProcessor);
        defaultProcessor.addHandler(ConfigurationModel.class, getConfigurationModelHandlerFactoryMethod());
        defaultProcessor.addHandler(ContextNameModel.class, ContextNameModelHandler::makeInstance);
        defaultProcessor.addHandler(LoggerContextListenerModel.class, LoggerContextListenerModelHandler::makeInstance);

        defaultProcessor.addHandler(InsertFromJNDIModel.class, InsertFromJNDIModelHandler::makeInstance);

        defaultProcessor.addHandler(AppenderModel.class, AppenderModelHandler::makeInstance);
        defaultProcessor.addHandler(AppenderRefModel.class, AppenderRefModelHandler::makeInstance);
        defaultProcessor.addHandler(RootLoggerModel.class, RootLoggerModelHandler::makeInstance);
        defaultProcessor.addHandler(LoggerModel.class, LoggerModelHandler::makeInstance);
        defaultProcessor.addHandler(LevelModel.class, LevelModelHandler::makeInstance);

        defaultProcessor.addAnalyser(LoggerModel.class,
                () -> new RefContainerDependencyAnalyser(context, LoggerModel.class));

        defaultProcessor.addAnalyser(RootLoggerModel.class,
                () -> new RefContainerDependencyAnalyser(context, RootLoggerModel.class));

        defaultProcessor.addAnalyser(AppenderModel.class,
                () -> new RefContainerDependencyAnalyser(context, AppenderModel.class));

        defaultProcessor.addAnalyser(AppenderRefModel.class, () -> new AppenderRefDependencyAnalyser(context));

        sealModelFilters(defaultProcessor);

    }

    public ModelHandlerFactoryMethod getConfigurationModelHandlerFactoryMethod() {
        if(configurationModelHandlerFactoryMethod == null) {
            //System.out.println("returning default ConfigurationModelHandler::makeInstance;");
            return  ConfigurationModelHandler::makeInstance;
        } else {
            //System.out.println("returning set "+configurationModelHandlerFactoryMethod);
            return configurationModelHandlerFactoryMethod;
        }
    }


    /**
     * Allow configurators to override the factory method for ConfigurationModelHandler
     *
     */
    public void setConfigurationModelHandlerFactoryMethod(ModelHandlerFactoryMethod cmhfm) {
        //System.out.println("setConfigurationModelHandlerFactoryMethod called with "+cmhfm);
        this.configurationModelHandlerFactoryMethod = cmhfm;
    }

}
