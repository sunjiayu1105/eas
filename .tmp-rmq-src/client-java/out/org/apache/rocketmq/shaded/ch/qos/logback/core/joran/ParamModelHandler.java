package org.apache.rocketmq.shaded.ch.qos.logback.core.joran;

import org.apache.rocketmq.shaded.ch.qos.logback.core.Context;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.util.PropertySetter;
import org.apache.rocketmq.shaded.ch.qos.logback.core.joran.util.beans.BeanDescriptionCache;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.Model;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.ParamModel;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelHandlerBase;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelHandlerException;
import org.apache.rocketmq.shaded.ch.qos.logback.core.model.processor.ModelInterpretationContext;

public class ParamModelHandler extends ModelHandlerBase {

    private final BeanDescriptionCache beanDescriptionCache;

    public ParamModelHandler(Context context, BeanDescriptionCache beanDescriptionCache) {
        super(context);
        this.beanDescriptionCache = beanDescriptionCache;
    }

    static public ModelHandlerBase makeInstance(Context context, ModelInterpretationContext ic) {
        return new ParamModelHandler(context, ic.getBeanDescriptionCache());
    }

    @Override
    protected Class<ParamModel> getSupportedModelClass() {
        return ParamModel.class;
    }

    @Override
    public void handle(ModelInterpretationContext intercon, Model model) throws ModelHandlerException {

        ParamModel paramModel = (ParamModel) model;

        String valueStr = intercon.subst(paramModel.getValue());

        Object o = intercon.peekObject();

        PropertySetter propSetter = new PropertySetter(beanDescriptionCache, o);
        propSetter.setContext(context);

        // allow for variable substitution for name as well
        String finalName = intercon.subst(paramModel.getName());
        propSetter.setProperty(finalName, valueStr);
    }

}
