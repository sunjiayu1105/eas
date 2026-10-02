package org.apache.rocketmq.shaded.ch.qos.logback.classic.model.processor;

import org.apache.rocketmq.shaded.ch.qos.logback.classic.Level;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.Logger;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.LoggerContext;
import org.apache.rocketmq.shaded.ch.qos.logback.classic.model.RootLoggerModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.Context;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelHandlerBase;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelHandlerException;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelInterpretationContext;
import org.apache.rocketmq.shaded.ch.qos.logback.core.util.OptionHelper;

public class RootLoggerModelHandler extends ModelHandlerBase {

    Logger root;
    boolean inError = false;

    public RootLoggerModelHandler(Context context) {
        super(context);
    }

    static public RootLoggerModelHandler makeInstance(Context context, ModelInterpretationContext ic) {
        return new RootLoggerModelHandler(context);
    }

    protected Class<RootLoggerModel> getSupportedModelClass() {
        return RootLoggerModel.class;
    }

    @Override
    public void handle(ModelInterpretationContext mic, Model model) throws ModelHandlerException {
        inError = false;

        RootLoggerModel rootLoggerModel = (RootLoggerModel) model;

        LoggerContext loggerContext = (LoggerContext) this.context;
        root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);

        String levelStr = mic.subst(rootLoggerModel.getLevel());
        if (!OptionHelper.isNullOrEmpty(levelStr)) {
            Level level = Level.toLevel(levelStr);
            addInfo("Setting level of ROOT logger to " + level);
            root.setLevel(level);
        }

        mic.pushObject(root);
    }

    @Override
    public void postHandle(ModelInterpretationContext mic, Model model) {
        if (inError) {
            return;
        }
        Object o = mic.peekObject();
        if (o != root) {
            addWarn("The object [" + o + "] on the top the of the stack is not the root logger");
        } else {
            mic.popObject();
        }
    }

}
