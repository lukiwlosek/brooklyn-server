/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.core.workflow;

import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import freemarker.template.TemplateHashModel;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.classloading.BrooklynClassLoadingContext;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.resolve.jackson.BeanWithTypeUtils;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.text.TemplateProcessor;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.javalang.ClassLoadingContext;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public class WorkflowExpressionResolution {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExpressionResolution.class);
    private final WorkflowExecutionContext context;

    public WorkflowExpressionResolution(WorkflowExecutionContext context) {
        this.context = context;
    }

    TemplateModel ifNoMatches() {
        // TODO placeholder method. should we trigger an error if not found (that is what the code below does)
        // alternatively we could return null, or leave the expression in place
        return null;
    }

    class WorkflowFreemarkerModel implements TemplateHashModel {
        @Override
        public TemplateModel get(String key) throws TemplateModelException {
            if ("workflow".equals(key)) {
                return new WorkflowExplicitModel();
            }
            if ("entity".equals(key)) {
                Entity entity = context.getEntity();
                if (entity!=null) {
                    return TemplateProcessor.EntityAndMapTemplateModel.forEntity(entity, null);
                }
            }

            // TODO
            //workflow.current_step.output.somevar
            //workflow.current_step.input.somevar
            //workflow.previous_step.output.somevar

            //workflow.scratch.somevar
            Object candidate = context.workflowScratchVariables.get(key);
            if (candidate!=null) return TemplateProcessor.wrapAsTemplateModel(candidate);

            // TODO
            //workflow.input.somevar

            return ifNoMatches();
        }

        @Override
        public boolean isEmpty() throws TemplateModelException {
            return false;
        }
    }

    class WorkflowExplicitModel implements TemplateHashModel {
        @Override
        public TemplateModel get(String key) throws TemplateModelException {
            // TODO
            //name
            //id (a token representing an item uniquely within its root instance)
            //task_id (the ID of the current corresponding Brooklyn Task)
            //link (a link in the UI to this instance of workflow or step)
            //error (if there is an error in scope)
            //current_step.yyy and previous_step.yyy (where yyy is any of the above)
            //TODO need a class WorkflowStepModel for above and below
            //step.xxx.yyy ? - where yyy is any of the above and xxx any step id

            return ifNoMatches();
        }

        @Override
        public boolean isEmpty() throws TemplateModelException {
            return false;
        }
    }

    public <T> T resolveWithTemplates(Object expression, TypeToken<T> type) {
        expression = processTemplateExpression(expression);
        try {
            // try yaml coercion, as values are normally set from yaml and will be raw at this stage
            return BeanWithTypeUtils.convert(((EntityInternal)this.context.getEntity()).getManagementContext(), expression, type, true,
                    RegisteredTypes.getClassLoadingContext(context.getEntity()), false);
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            try {
                // fallback to simple coercion
                return TypeCoercions.coerce(expression, type);
            } catch (Exception e2) {
                Exceptions.propagateIfFatal(e2);
                throw Exceptions.propagate(e);
            }
        }
    }

    public Object processTemplateExpression(Object expression) {
        if (expression instanceof String) return processTemplateExpressionString((String)expression);
        if (expression instanceof Map) return processTemplateExpressionMap((Map)expression);
        if (expression instanceof Collection) return processTemplateExpressionCollection((Collection)expression);
        return expression;
    }

    public Object processTemplateExpressionString(String expression) {
        TemplateHashModel model = new WorkflowFreemarkerModel();
        return TemplateProcessor.processTemplateContents(expression, model);
    }

    public Map<?,?> processTemplateExpressionMap(Map<?,?> object) {
        Map<Object,Object> result = MutableMap.of();
        object.forEach((k,v) -> result.put(processTemplateExpression(k), processTemplateExpression(v)));
        return result;

    }

    protected Collection<?> processTemplateExpressionCollection(Collection<?> object) {
        return object.stream().map(x -> processTemplateExpression(x)).collect(Collectors.toList());
    }

}
