/*****************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *****************************************************************************/
package ee.jakarta.tck.ai.agent.core.workflow;

import ee.jakarta.tck.ai.agent.core.workflow.agent.VoidPhasesAgent;
import ee.jakarta.tck.ai.agent.framework.junit.anno.Assertion;
import ee.jakarta.tck.ai.agent.framework.junit.anno.Deployed;
import ee.jakarta.tck.ai.agent.framework.stub.StubLargeLanguageModel;
import ee.jakarta.tck.ai.agent.framework.trace.ExecutionTraceRecorder;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral TCK tests verifying that {@code void} return types are valid for
 * {@code @Trigger}, {@code @Action}, and {@code @Outcome} phase methods, and
 * that no phantom values are propagated downstream from a void phase.
 *
 * @see VoidPhasesAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowVoidPhasesTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "voidPhases.war")
                .addClasses(
                        VoidPhasesAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        VoidPhasesAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-027",
               section = "Section 3.1: Trigger - Void Return",
               strategy = "Verify a void @Trigger method executes successfully")
    public void testVoidTriggerExecutes() {
        workflowEvent.fire(new WorkflowEvent("void-trigger"));

        assertTrue(VoidPhasesAgent.RECORDER.wasMethodCalled("onEvent"),
                "Void @Trigger method must execute");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-028",
               section = "Section 3.3: Action - Void Return",
               strategy = "Verify a void @Action method executes successfully")
    public void testVoidActionExecutes() {
        workflowEvent.fire(new WorkflowEvent("void-action"));

        assertTrue(VoidPhasesAgent.RECORDER.wasMethodCalled("perform"),
                "Void @Action method must execute");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-029",
               section = "Section 3.4: Outcome - Void Return",
               strategy = "Verify a void @Outcome method executes successfully")
    public void testVoidOutcomeExecutes() {
        workflowEvent.fire(new WorkflowEvent("void-outcome"));

        assertTrue(VoidPhasesAgent.RECORDER.wasMethodCalled("conclude"),
                "Void @Outcome method must execute");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-030",
               section = "Section 3: Workflow Phase Semantics - Void Propagation",
               strategy = "Verify void phases complete in order without propagating phantom values")
    public void testVoidPhasesExecuteInOrder() {
        workflowEvent.fire(new WorkflowEvent("void-order"));

        assertEquals(3, VoidPhasesAgent.RECORDER.size(),
                "Three phases must execute: trigger, action, outcome");
        assertTrue(VoidPhasesAgent.RECORDER.wasCalledInOrder("trigger", "action", "outcome"),
                "Void phases must execute in declared order");
    }
}
