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

import ee.jakarta.tck.ai.agent.core.workflow.agent.MultiActionAgent;
import ee.jakarta.tck.ai.agent.framework.junit.anno.Assertion;
import ee.jakarta.tck.ai.agent.framework.junit.anno.Deployed;
import ee.jakarta.tck.ai.agent.framework.stub.StubLargeLanguageModel;
import ee.jakarta.tck.ai.agent.framework.trace.ExecutionTraceRecorder;
import ee.jakarta.tck.ai.agent.framework.trace.ExecutionTraceRecorder.TraceEntry;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral TCK tests verifying multi-{@code @Action} execution semantics:
 * declaration order, sequential processing, prior-action result injection,
 * and that all actions complete before the {@code @Outcome} phase begins.
 *
 * @see MultiActionAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowMultiActionTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "multiAction.war")
                .addClasses(
                        MultiActionAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        MultiActionAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-019",
               section = "Section 3.3: Action - Multiple Actions",
               strategy = "Verify that multiple @Action methods all execute in one workflow run")
    public void testAllActionsExecute() {
        workflowEvent.fire(new WorkflowEvent("multi-action"));

        List<TraceEntry> actionEntries = MultiActionAgent.RECORDER.getEntriesForPhase("action");
        assertEquals(3, actionEntries.size(), "All 3 actions must execute");
        assertTrue(MultiActionAgent.RECORDER.wasMethodCalled("firstAction"));
        assertTrue(MultiActionAgent.RECORDER.wasMethodCalled("secondAction"));
        assertTrue(MultiActionAgent.RECORDER.wasMethodCalled("thirdAction"));
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-020",
               section = "Section 3.3: Action - Declaration Order",
               strategy = "Verify that multiple @Action methods execute in declaration order")
    public void testActionsExecuteInDeclarationOrder() {
        workflowEvent.fire(new WorkflowEvent("multi-action-order"));

        List<TraceEntry> actionEntries = MultiActionAgent.RECORDER.getEntriesForPhase("action");
        assertEquals("firstAction", actionEntries.get(0).methodName());
        assertEquals("secondAction", actionEntries.get(1).methodName());
        assertEquals("thirdAction", actionEntries.get(2).methodName());
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-021",
               section = "Section 3.3: Action - Sequential Processing",
               strategy = "Verify a later action can inject the return value of an earlier action")
    public void testLaterActionReceivesEarlierActionResult() {
        workflowEvent.fire(new WorkflowEvent("sequential"));

        TraceEntry second = MultiActionAgent.RECORDER.getEntriesForPhase("action").get(1);
        TraceEntry third = MultiActionAgent.RECORDER.getEntriesForPhase("action").get(2);

        assertTrue(Arrays.asList(second.parameters()).contains(1),
                "secondAction must receive firstAction's Integer return value");
        assertTrue(Arrays.asList(third.parameters()).contains(1),
                "thirdAction must receive firstAction's Integer return value");
        assertTrue(Arrays.asList(third.parameters()).contains(2L),
                "thirdAction must receive secondAction's Long return value");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-022",
               section = "Section 3.3: Action - Lifecycle Integrity",
               strategy = "Verify all actions complete before @Outcome begins")
    public void testAllActionsCompleteBeforeOutcome() {
        workflowEvent.fire(new WorkflowEvent("lifecycle"));

        assertTrue(MultiActionAgent.RECORDER.wasCalledInOrder(
                        "trigger", "action", "action", "action", "outcome"),
                "Phases must execute: trigger, three actions, then outcome");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-023",
               section = "Section 3.4: Outcome - Multi-Action State",
               strategy = "Verify @Outcome receives return values from all prior actions")
    public void testOutcomeReceivesAllActionResults() {
        workflowEvent.fire(new WorkflowEvent("all-results"));

        TraceEntry outcome = MultiActionAgent.RECORDER.getEntriesForPhase("outcome").get(0);
        List<Object> params = Arrays.asList(outcome.parameters());
        assertTrue(params.contains(1), "Outcome must receive firstAction Integer result");
        assertTrue(params.contains(2L), "Outcome must receive secondAction Long result");
        assertTrue(params.contains(3.0), "Outcome must receive thirdAction Double result");
    }
}
