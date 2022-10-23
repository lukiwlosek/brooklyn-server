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
package org.apache.brooklyn.camp.brooklyn;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.resolve.jackson.BeanWithTypeUtils;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.typereg.BasicBrooklynTypeRegistry;
import org.apache.brooklyn.core.typereg.BasicTypeImplementationPlan;
import org.apache.brooklyn.core.typereg.JavaClassNameTypePlanTransformer;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.core.workflow.*;
import org.apache.brooklyn.core.workflow.steps.LogWorkflowStep;
import org.apache.brooklyn.core.workflow.store.WorkflowStatePersistenceViaSensors;
import org.apache.brooklyn.entity.software.base.WorkflowSoftwareProcess;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.apache.brooklyn.location.byon.FixedListMachineProvisioningLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.location.winrm.WinrmWorkflowStep;
import org.apache.brooklyn.tasks.kubectl.ContainerWorkflowStep;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.test.ClassLogWatcher;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool;
import org.apache.brooklyn.util.core.json.BrooklynObjectsJsonMapper;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.apache.brooklyn.util.core.internal.ssh.ExecCmdAsserts.assertExecContains;
import static org.apache.brooklyn.util.core.internal.ssh.ExecCmdAsserts.assertExecsContain;

public class WorkflowYamlTest extends AbstractYamlTest {

    static final String VERSION = "0.1.0-SNAPSHOT";

    @SuppressWarnings("deprecation")
    static RegisteredType addRegisteredTypeBean(ManagementContext mgmt, String symName, Class<?> clazz) {
        RegisteredType rt = RegisteredTypes.bean(symName, VERSION,
                new BasicTypeImplementationPlan(JavaClassNameTypePlanTransformer.FORMAT, clazz.getName()));
        ((BasicBrooklynTypeRegistry)mgmt.getTypeRegistry()).addToLocalUnpersistedTypeRegistry(rt, false);
        return rt;
    }

    static RegisteredType addRegisteredTypeSpec(ManagementContext mgmt, String symName, Class<?> clazz) {
        RegisteredType rt = RegisteredTypes.spec(symName, VERSION,
                new BasicTypeImplementationPlan(JavaClassNameTypePlanTransformer.FORMAT, clazz.getName()));
        RegisteredTypes.addSuperType(rt, Policy.class);

        ((BasicBrooklynTypeRegistry)mgmt.getTypeRegistry()).addToLocalUnpersistedTypeRegistry(rt, false);
        return rt;
    }

    public static void addWorkflowTypes(ManagementContext mgmt) {
        WorkflowBasicTest.addWorkflowStepTypes(mgmt);

        addRegisteredTypeBean(mgmt, "container", ContainerWorkflowStep.class);
        addRegisteredTypeBean(mgmt, "winrm", WinrmWorkflowStep.class);

        addRegisteredTypeBean(mgmt, "workflow-effector", WorkflowEffector.class);
        addRegisteredTypeBean(mgmt, "workflow-sensor", WorkflowSensor.class);
        addRegisteredTypeSpec(mgmt, "workflow-policy", WorkflowPolicy.class);
    }

    @BeforeMethod(alwaysRun = true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        addWorkflowTypes(mgmt());
    }

    @Test
    public void testWorkflowEffector() throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      steps:",
                "        - type: no-op",
                "        - type: set-sensor",
                "          input:",
                "            sensor: foo",
                "            value: bar",
                "        - set-sensor integer bar = 1",
                "        - set-config integer foo = 2",
                "");
        waitForApplicationTasks(app);

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();

        Task<?> invocation = entity.invoke(effector, null);
        Object result = invocation.getUnchecked();
        Asserts.assertNull(result);
        Dumper.dumpInfo(invocation);

        EntityAsserts.assertAttributeEquals(entity, Sensors.newSensor(Object.class, "foo"), "bar");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newSensor(Object.class, "bar"), 1);
        EntityAsserts.assertConfigEquals(entity, ConfigKeys.newConfigKey(Object.class, "foo"), 2);
    }

    @Test
    public void testWorkflowSensorTrigger() throws Exception {
        doTestWorkflowSensor("triggers: theTrigger", Duration.seconds(1)::isLongerThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowSensorPeriod() throws Exception {
        doTestWorkflowSensor("period: 2s", Duration.seconds(2)::isShorterThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowSensorTriggerWithCondition() throws Exception {
        doTestWorkflowSensor("condition: { sensor: not_exist }\n" + "triggers: theTrigger", null);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowSensorPeriodWithCondition() throws Exception {
        doTestWorkflowSensor("condition: { sensor: not_exist }\n" + "period: 200 ms", null);
    }

    @Test
    public void testWorkflowPolicyTrigger() throws Exception {
        doTestWorkflowPolicy("triggers: theTrigger", Duration.seconds(1)::isLongerThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowPolicyPeriod() throws Exception {
        doTestWorkflowPolicy("period: 2s", Duration.seconds(2)::isShorterThan);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowPolicyTriggerWithCondition() throws Exception {
        doTestWorkflowPolicy("condition: { sensor: not_exist }\n" + "triggers: theTrigger", null);
    }

    @Test(groups="Integration") // because delay
    public void testWorkflowPolicyPeriodWithCondition() throws Exception {
        doTestWorkflowPolicy("condition: { sensor: not_exist }\n" + "period: 200 ms", null);
    }

    void doTestWorkflowSensor(String triggers, Predicate<Duration> timeCheckOrNullIfShouldFail) throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-sensor",
                "    brooklyn.config:",
                "      sensor: myWorkflowSensor",   // supports old syntax { name: x, targetType: T } or new syntax simple { sensor: Name } or full { sensor: { name: Name, type: T } }
                Strings.indent(6, triggers),
                "      steps:",
                "        - let v = ${entity.sensor.myWorkflowSensor.v} + 1 ?? 0",
                "        - type: let",
                "          variable: out",
                "          value: |",
                "            ignored sample output before doc",
                "            ---",
                "            foo: bar",
                "            v: ${v}",
                "        - let trimmed map x = ${out}",
                "        - return ${x}",
                "");

        Stopwatch sw = Stopwatch.createStarted();
        waitForApplicationTasks(app);
        Duration d1 = Duration.of(sw);

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        AttributeSensor<Object> s = Sensors.newSensor(Object.class, "myWorkflowSensor");

        if (timeCheckOrNullIfShouldFail!=null) {
            EntityAsserts.assertAttributeEventuallyNonNull(entity, s);
            Duration d2 = Duration.of(sw).subtract(d1);
            // initial set should be soon after startup
            Asserts.assertThat(d2, Duration.millis(500)::isLongerThan);
            EntityAsserts.assertAttributeEqualsEventually(entity, s, MutableMap.of("foo", "bar", "v", 0));

            entity.sensors().set(Sensors.newStringSensor("theTrigger"), "go");
            EntityAsserts.assertAttributeEqualsEventually(entity, s, MutableMap.of("foo", "bar", "v", 1));
            Duration d3 = Duration.of(sw).subtract(d2);
            // the next iteration should obey the time constraint specified above
            if (!timeCheckOrNullIfShouldFail.test(d3)) Asserts.fail("Timing error, took " + d3);
        } else {
            EntityAsserts.assertAttributeEqualsContinually(entity, s, null);
        }

        WorkflowExecutionContext lastWorkflowContext = new WorkflowStatePersistenceViaSensors(mgmt()).getWorkflows(entity).values().iterator().next();
        List<Object> defs = lastWorkflowContext.getStepsDefinition();
        // step definitions should not be resolved by jackson
        defs.forEach(def -> Asserts.assertThat(def, d -> !(d instanceof WorkflowStepDefinition)));
    }

    public void doTestWorkflowPolicy(String triggers, Predicate<Duration> timeCheckOrNullIfShouldFail) throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.policies:",
                "  - type: workflow-policy",
                "    brooklyn.config:",
                "      name: Set myWorkflowSensor",
                "      id: set-my-workflow-sensor",
                Strings.indent(6, triggers),
                "      steps:",
                "        - let v = ${entity.sensor.myWorkflowSensor.v} + 1 ?? 0",
                "        - type: let",
                "          variable: out",
                "          value: |",
                "            ignored sample output before doc",
                "            ---",
                "            foo: bar",
                "            v: ${v}",
                "        - let trimmed map x = ${out}",
                "        - set-sensor myWorkflowSensor = ${x}",
                "");

        Stopwatch sw = Stopwatch.createStarted();
        waitForApplicationTasks(app);
        Duration d1 = Duration.of(sw);

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Policy policy = entity.policies().asList().stream().filter(p -> p instanceof WorkflowPolicy).findAny().get();
        Asserts.assertEquals(policy.getDisplayName(), "Set myWorkflowSensor");
        // should really ID be settable from flag?
        Asserts.assertEquals(policy.getId(), "set-my-workflow-sensor");

        AttributeSensor<Object> s = Sensors.newSensor(Object.class, "myWorkflowSensor");

        if (timeCheckOrNullIfShouldFail!=null) {
//            EntityAsserts.assertAttributeEventuallyNonNull(entity, s);
            EntityAsserts.assertAttributeEquals(entity, s, null);
            Duration d2 = Duration.of(sw).subtract(d1);
            // initial set should be soon after startup
            Asserts.assertThat(d2, Duration.millis(500)::isLongerThan);
//            EntityAsserts.assertAttributeEqualsEventually(entity, s, MutableMap.of("foo", "bar", "v", 0));

            entity.sensors().set(Sensors.newStringSensor("theTrigger"), "go");
            EntityAsserts.assertAttributeEqualsEventually(entity, s, MutableMap.of("foo", "bar", "v", 0));
//            EntityAsserts.assertAttributeEqualsEventually(entity, s, MutableMap.of("foo", "bar", "v", 1));
            Duration d3 = Duration.of(sw).subtract(d2);
            // the next iteration should obey the time constraint specified above
            if (!timeCheckOrNullIfShouldFail.test(d3)) Asserts.fail("Timing error, took " + d3);
        } else {
            EntityAsserts.assertAttributeEqualsContinually(entity, s, null);
        }
    }

    ClassLogWatcher lastLogWatcher;

    Object invokeWorkflowStepsWithLogging(String ...stepLines) throws Exception {
        try (ClassLogWatcher logWatcher = new ClassLogWatcher(LogWorkflowStep.class)) {
            lastLogWatcher = logWatcher;

            // Declare workflow in a blueprint, add various log steps.
            Entity app = createAndStartApplication(
                    "services:",
                    "- type: " + BasicEntity.class.getName(),
                    "  brooklyn.initializers:",
                    "  - type: workflow-effector",
                    "    brooklyn.config:",
                    "      name: myWorkflow",
                    "      steps:",
                    Strings.indent(8, Strings.lines(stepLines)));
            waitForApplicationTasks(app);

            // Deploy the blueprint.
            Entity entity = Iterables.getOnlyElement(app.getChildren());
            Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();
            Task<?> invocation = entity.invoke(effector, null);
            return invocation.getUnchecked();
        }
    }

    void assertLogStepMessages(String ...lines) {
        Assert.assertEquals(lastLogWatcher.getMessages(),
                Arrays.asList(lines));
    }

    @Test
    public void testWorkflowEffectorLogStep() throws Exception {
        invokeWorkflowStepsWithLogging(
                "- log test message 1",
                "- type: log",
                "  id: second",
                "  name: Second Step",
                "  message: test message 2, step '${workflow.current_step.name}' id ${workflow.current_step.step_id} in workflow '${workflow.name}'");

        assertLogStepMessages(
                "test message 1",
                "test message 2, step 'Second Step' id second in workflow 'Workflow for effector myWorkflow'");
    }

    @Test
    public void testWorkflowPropertyNext() throws Exception {
        invokeWorkflowStepsWithLogging(
                "- s: log going to A",
                "  next: A",
                "- s: log now at B",
                "  next: end",
                "  id: B",
                "- s: log now at A",
                "  id: A",
                "  next: B");
        assertLogStepMessages(
                    "going to A",
                    "now at A",
                    "now at B");
    }

//    // TODO test timeout
//    @Test
//    public void testTimeoutWithInfiniteLoop() throws Exception {
//        invokeWorkflowStepsWithLogging(
//                "        - s: log going to A",
//                        "          next: A",
//                        "        - s: log now at B",
//                        "          id: B",
//                        "        - s: sleep 100ms",
//                        "          next: B",
//                        "        - s: log now at A",
//                        "          id: A",
//                        "          next: B");
//        // TODO assert it takes at least 100ms, but less than 5s
//        assertLogStepMessages(
//                ...);
//    }

    void doTestWorkflowCondition(String setCommand, String logAccess, String conditionAccess) throws Exception {
        invokeWorkflowStepsWithLogging(
                    "- log start",
                    "- " + setCommand + " color = blue",
                    "- id: log-color",
                    "  s: log color " + logAccess,
                    "-",
                    "  s: log not blue",
                    "  condition:",
                    "    " + conditionAccess,
                    "    assert: { when: present, java-instance-of: string }",
                    "    not: { equals: blue }",
                    "-",
                    "  type: no-op",
                    "  next: make-red",
                    "  condition:",
                    "    " + conditionAccess,
                    "    equals: blue",
                    "-",
                    "  type: no-op",
                    "  next: log-end",
                    "- id: make-red",
                    "  s: " + setCommand + " color = red",
                    "  next: log-color",
                    "- id: log-end",
                    "  s: log end",
                    "");
        assertLogStepMessages(
                    "start", "color blue", "color red", "not blue", "end");
    }

    @Test
    public void testWorkflowSensorCondition() throws Exception {
        doTestWorkflowCondition("set-sensor", "${entity.sensor.color}", "sensor: color");
    }

    @Test
    public void testWorkflowVariableInCondition() throws Exception {
        doTestWorkflowCondition("let", "${color}", "target: ${color}");
    }

    @Test
    public void testEffectorToSetColorSensorConditionally() throws Exception {
        // Declare workflow in a blueprint, add various log steps.
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      parameters:\n" +
                "        color:\n" +
                "          type: string\n" +
                "          description: What color do you want to set?\n" +
                "\n" +
                "      steps:\n" +
//                "        - let old_color = ${(entity.sensor.color)! \"unset\"}\n" +
                        // above does not work. but the below is recommended
                "        - let old_color = ${entity.sensor.color} ?? \"unset\"\n" +

                        // alternative (supported) if not using nullish operator
//                "        - let old_color = unset\n" +
//                "        - s: let old_color = ${entity.sensor.color}\n" +
//                "          condition:\n" +
//                "            sensor: color\n" +
//                "            when: present_non_null\n" +

                "        - log changing color sensor from ${old_color} to ${color}\n" +
                "        - set-sensor color = ${color}\n" +
                "        - s: set-sensor color_is_red = true\n" +
                "          condition:\n" +
                "            sensor: color\n" +
                "            equals: red\n" +
                "          next: end\n" +
                "        - set-sensor color_is_red = false");

        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();

        entity.invoke(effector, MutableMap.of("color", "red")).get();
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color"), "red");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color_is_red"), "true");

        entity.invoke(effector, MutableMap.of("color", "blue")).get();
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color"), "blue");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color_is_red"), "false");

        entity.invoke(effector, MutableMap.of("color", "red")).get();
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color"), "red");
        EntityAsserts.assertAttributeEquals(entity, Sensors.newStringSensor("color_is_red"), "true");

    }

    @Test
    public void testInvalidStepsFailDeployment() throws Exception {
        try {
            createAndStartApplication(
                    "services:",
                    "- type: " + BasicEntity.class.getName(),
                    "  brooklyn.initializers:",
                    "  - type: workflow-effector",
                    "    brooklyn.config:",
                    "      name: myWorkflow",
                            "      steps:\n" +
                            "        - unsupported-type"
            );
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContainsIgnoreCase(e, "resolve step", "unsupported-type");
        }
    }

    @Test
    public void testWorkflowSoftwareProcessAsYaml() throws Exception {
        RecordingSshTool.clear();

        FixedListMachineProvisioningLocation loc = mgmt().getLocationManager().createLocation(LocationSpec.create(FixedListMachineProvisioningLocation.class)
                .configure(FixedListMachineProvisioningLocation.MACHINE_SPECS, ImmutableList.<LocationSpec<? extends MachineLocation>>of(
                        LocationSpec.create(SshMachineLocation.class)
                                .configure("address", "1.2.3.4")
                                .configure(SshMachineLocation.SSH_TOOL_CLASS, RecordingSshTool.class.getName()))));

        Application app = createApplicationUnstarted(
                "services:",
                "- type: " + WorkflowSoftwareProcess.class.getName(),
                "  brooklyn.config:",
                "    "+BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION.getName()+": true",
                "    install.workflow:",
                "      steps:",
                "        - ssh installWorkflow",
                "        - set-sensor boolean installed = true",
                "        - type: no-op",
                "    stop.workflow:",
                "      steps:",
                "        - ssh stopWorkflow",
                "        - set-sensor boolean stopped = true"
        );

        Entity child = app.getChildren().iterator().next();
        List<Object> steps = child.config().get(WorkflowSoftwareProcess.INSTALL_WORKFLOW).peekSteps();
        // should not be resolved yet
        steps.forEach(def -> Asserts.assertThat(def, d -> !(d instanceof WorkflowStepDefinition)));

        ((Startable)app).start(MutableList.of(loc));

        assertExecsContain(RecordingSshTool.getExecCmds(), ImmutableList.of(
                "installWorkflow"));

        EntityAsserts.assertAttributeEquals(child, Sensors.newSensor(Boolean.class, "installed"), true);
        EntityAsserts.assertAttributeEquals(child, Sensors.newSensor(Boolean.class, "stopped"), null);

        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_UP, true);
        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        WorkflowExecutionContext lastWorkflowContext = new WorkflowStatePersistenceViaSensors(mgmt()).getWorkflows(child).values().iterator().next();
        List<Object> defs = lastWorkflowContext.getStepsDefinition();
        // step definitions should not be resolved by jackson
        defs.forEach(def -> Asserts.assertThat(def, d -> !(d instanceof WorkflowStepDefinition)));

        ((Startable)app).stop();

        EntityAsserts.assertAttributeEquals(child, Sensors.newSensor(Boolean.class, "stopped"), true);
        assertExecContains(RecordingSshTool.getLastExecCmd(), "stopWorkflow");

        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_UP, false);
        EntityAsserts.assertAttributeEqualsEventually(child, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
    }

    @Test
    public void testConditionNormal() throws Exception {
        Asserts.assertEquals(doTestCondition("regex: .*oh no.*"), "expected failure");
    }
    @Test
    public void testConditionBadSerialization() throws Exception {
        Asserts.assertFailsWith(() -> doTestCondition("- regex: .*oh no.*"),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "unresolveable", "regex"));
    }
    @Test
    public void testConditionBadExpression() throws Exception {
        // TODO would be nice if it could silently ignore this condition
        // TODO also handle multi-line errors (eg from freemarker)
        Asserts.assertFailsWith(() -> doTestCondition(Strings.lines(
                "any:",
                    "- regex: .*oh no.*",
                    "- target: ${bad_var}",
                    "  when: absent")),
                e -> Asserts.expectedFailureContainsIgnoreCase(e, "unresolveable", "bad_var"));
    }

    Object doTestCondition(String lines) throws Exception {
        Entity app = createAndStartApplication(
                "services:",
                "- type: " + BasicEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: workflow-effector",
                "    brooklyn.config:",
                "      name: myWorkflow",
                "      steps:",
                "        - step: fail message oh no",
                "          on-error:",
                "          - step: return expected failure",
                "            condition:",
                Strings.indent(14, lines));
        waitForApplicationTasks(app);
        Entity entity = Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName("myWorkflow").get();

        Task<?> invocation = entity.invoke(effector, null);
        return invocation.getUnchecked();
    }

}
