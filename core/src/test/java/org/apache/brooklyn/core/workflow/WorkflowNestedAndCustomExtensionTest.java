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

import com.google.common.collect.Iterables;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.resolve.jackson.BeanWithTypePlanTransformer;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.BrooklynMgmtUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.core.typereg.BasicTypeImplementationPlan;
import org.apache.brooklyn.core.workflow.steps.LogWorkflowStep;
import org.apache.brooklyn.core.workflow.steps.utils.WorkflowConcurrency;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.test.ClassLogWatcher;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.apache.brooklyn.util.yaml.Yamls;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class WorkflowNestedAndCustomExtensionTest extends BrooklynMgmtUnitTestSupport {

    protected void loadTypes() {
        WorkflowBasicTest.addWorkflowStepTypes(mgmt);
    }

    public RegisteredType addBeanWithType(String typeName, String version, String plan) {
        loadTypes();
        return BrooklynAppUnitTestSupport.addRegisteredTypeBean(mgmt, typeName, version,
                new BasicTypeImplementationPlan(BeanWithTypePlanTransformer.FORMAT, plan));
    }

    ClassLogWatcher lastLogWatcher;
    TestApplication app;

    Object invokeWorkflowStepsWithLogging(List<Object> steps) throws Exception {
        try (ClassLogWatcher logWatcher = new ClassLogWatcher(LogWorkflowStep.class)) {
            lastLogWatcher = logWatcher;

            loadTypes();
            app = mgmt.getEntityManager().createEntity(EntitySpec.create(TestApplication.class));

            WorkflowEffector eff = new WorkflowEffector(ConfigBag.newInstance()
                    .configure(WorkflowEffector.EFFECTOR_NAME, "myWorkflow")
                    .configure(WorkflowEffector.STEPS, steps));
            eff.apply((EntityLocal)app);

            Task<?> invocation = app.invoke(app.getEntityType().getEffectorByName("myWorkflow").get(), null);
            return invocation.getUnchecked();
        }
    }

    void assertLogStepMessages(String ...lines) {
        Assert.assertEquals(lastLogWatcher.getMessages(),
                Arrays.asList(lines));
    }

    @Test
    public void testNestedWorkflowBasic() throws Exception {
        Object output = invokeWorkflowStepsWithLogging(MutableList.of(
                MutableMap.of("type", "workflow",
                        "steps", MutableList.of("return done"))));
        Asserts.assertEquals(output, "done");
    }

    @Test
    public void testNestedWorkflowParametersForbiddenWhenUsedDirectly() throws Exception {
        Asserts.assertFailsWith(() -> invokeWorkflowStepsWithLogging(MutableList.of(
                        MutableMap.of("type", "workflow",
                                "parameters", MutableMap.of(),
                                "steps", MutableList.of("return done")))),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "parameters"));
    }

    @Test
    public void testExtendingAStepWhichWorksButIsMessyAroundParameters() throws Exception {
        /*
         * extending any step type is supported, but discouraged because of confusion and no parameter definitions;
         * the preferred way is to use the method in the following test
         */
        addBeanWithType("log-hi", "1", Strings.lines(
                "type: log",
                "message: hi ${name}",
                "input:",
                "  name: you"
        ));

        invokeWorkflowStepsWithLogging(MutableList.of(MutableMap.of("type", "log-hi", "input", MutableMap.of("name", "bob"))));
        assertLogStepMessages("hi bob");

        invokeWorkflowStepsWithLogging(MutableList.of(MutableMap.of("type", "log-hi")));
        assertLogStepMessages("hi you");
    }

    @Test
    public void testDefiningCustomWorkflowStep() throws Exception {
        addBeanWithType("log-hi", "1", Strings.lines(
                "type: workflow",
                "parameters:",
                "  name: {}",
                "steps:",
                "  - log hi ${name}"
        ));
        invokeWorkflowStepsWithLogging(MutableList.of(MutableMap.of("type", "log-hi", "input", MutableMap.of("name", "bob"))));
        assertLogStepMessages("hi bob");

        Asserts.assertFailsWith(() -> invokeWorkflowStepsWithLogging(MutableList.of(
                        MutableMap.of("type", "log-hi",
                                "steps", MutableList.of("return not allowed to override")))),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "steps"));
    }

    @Test
    public void testDefiningCustomWorkflowStepWithShorthand() throws Exception {
        addBeanWithType("log-hi", "1", Strings.lines(
                "type: workflow",
                "shorthand: ${name}",
                "parameters:",
                "  name: {}",
                "steps:",
                "  - log hi ${name}"
        ));
        invokeWorkflowStepsWithLogging(MutableList.of("log-hi bob"));
        assertLogStepMessages("hi bob");
    }

    @Test
    public void testDefiningCustomWorkflowStepWithOutput() throws Exception {
        addBeanWithType("log-hi", "1", Strings.lines(
                "type: workflow",
                "parameters:",
                "  name: {}",
                "steps:",
                "  - log hi ${name}",
                "output:",
                "  message: hi ${name}"
        ));
        Object output = invokeWorkflowStepsWithLogging(MutableList.of(MutableMap.of("type", "log-hi", "input", MutableMap.of("name", "bob"))));
        assertLogStepMessages("hi bob");
        Asserts.assertEquals(output, MutableMap.of("message", "hi bob"));

        // output can be overridden
        output = invokeWorkflowStepsWithLogging(MutableList.of(MutableMap.of("type", "log-hi", "input", MutableMap.of("name", "bob"), "output", "I said ${message}")));
        assertLogStepMessages("hi bob");
        Asserts.assertEquals(output, "I said hi bob");
    }

    @Test
    public void testTargetExplicitList() throws Exception {
        Object output;
        output = invokeWorkflowStepsWithLogging(MutableList.of(Iterables.getOnlyElement(Yamls.parseAll(Strings.lines(
                "type: workflow",
                "steps:",
                "  - type: workflow",
                "    target: 1..5",
                "    steps:",
                "    - let integer r = ${target} * 5 - ${target} * ${target}",
                "    - return ${r}",
                "    output: ${output}",
                "  - transform max = ${output} | max",
                "  - return ${max}",
                ""
        )))));
        Asserts.assertEquals(output, 6);
    }

    @Test
    public void testTargetChildren() throws Exception {
        Object output;
        output = invokeWorkflowStepsWithLogging(MutableList.of(Iterables.getOnlyElement(Yamls.parseAll(Strings.lines(
                "type: workflow",
                "steps:",
                "  - type: workflow",
                "    target: children",
                "    steps:",
                "    - return ${entity.id}",
                ""
        )))));
        Asserts.assertEquals(output, MutableList.of());

        TestEntity child1 = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity child2 = app.createAndManageChild(EntitySpec.create(TestEntity.class));

        output = app.invoke(app.getEntityType().getEffectorByName("myWorkflow").get(), null).getUnchecked();
        Asserts.assertEquals(output, MutableList.of(child1.getId(), child2.getId()));
    }

    @Test
    public void testWorkflowConcurrencyComputation() throws Exception {
        Asserts.assertEquals(WorkflowConcurrency.parse("3").apply(2d), 3d);
        Asserts.assertEquals(WorkflowConcurrency.parse("all").apply(2d), 2d);
        Asserts.assertEquals(WorkflowConcurrency.parse("max(1,all)").apply(2d), 2d);
        Asserts.assertEquals(WorkflowConcurrency.parse("50%").apply(10d), 5d);
        Asserts.assertEquals(WorkflowConcurrency.parse("max(50%,30%+1)").apply(10d), 5d);
        Asserts.assertEquals(WorkflowConcurrency.parse("min(50%,30%+1)").apply(10d), 4d);
        Asserts.assertEquals(WorkflowConcurrency.parse("max(1,min(-10,30%+1))").apply(10d), 1d);
        Asserts.assertEquals(WorkflowConcurrency.parse("max(1,min(-10,30%+1))").apply(20d), 7d);
        Asserts.assertEquals(WorkflowConcurrency.parse("max(1,min(-10,30%+1))").apply(15d), 5d);
        Asserts.assertEquals(WorkflowConcurrency.parse("max(1,min(-10,30%-2))").apply(15d), 2.5d);
    }

    @Test
    public void testTargetManyChildrenConcurrently() throws Exception {
        Object output;
        output = invokeWorkflowStepsWithLogging(MutableList.of(Iterables.getOnlyElement(Yamls.parseAll(Strings.lines(
                "type: workflow",
                "steps:",
                "  - type: workflow",
                "    target: children",
                "    concurrency: max(1,50%)",
                "    steps:",
                "    - let count = ${entity.parent.sensor.count}",
                "    - let inc = ${count} + 1",
                "    - step: set-sensor count = ${inc}",
                "      require: ${count}",
                "      sensor:",
                "        entity: ${entity.parent}",
                "      on-error:",
                "        - retry from start limit 20 backoff 5ms",  // repeat until count is ours to increment
                "    - transform go = ${entity.parent.attributeWhenReady.go} | wait",
                "    - return ${entity.id}",
                ""
        )))));
        Asserts.assertEquals(output, MutableList.of());

        AttributeSensor<Integer> COUNT = Sensors.newSensor(Integer.class, "count");
        AttributeSensor<String> GO = Sensors.newSensor(String.class, "go");

        app.sensors().set(COUNT, 0);
        List<Entity> children = MutableList.of();

//        // to test just one
//        app.sensors().set(GO, "now!");
//        children.add(app.createAndManageChild(EntitySpec.create(TestEntity.class)));
//        app.invoke(app.getEntityType().getEffectorByName("myWorkflow").get(), null).get();
//        app.sensors().set(COUNT, 0);
//        app.sensors().remove(GO);

        for (int i=children.size(); i<10; i++) children.add(app.createAndManageChild(EntitySpec.create(TestEntity.class)));

        Task<?> call = app.invoke(app.getEntityType().getEffectorByName("myWorkflow").get(), null);
        EntityAsserts.assertAttributeEqualsEventually(app, COUNT, 5);

//        // for extra check
//        Time.sleep(Duration.millis(100));

        // should only be allowed to run 5
        EntityAsserts.assertAttributeEquals(app, COUNT, 5);
        Asserts.assertFalse(call.isDone());

        app.sensors().set(GO, "now!");

        Asserts.assertEquals(call.getUnchecked(), children.stream().map(Entity::getId).collect(Collectors.toList()));
    }

}
