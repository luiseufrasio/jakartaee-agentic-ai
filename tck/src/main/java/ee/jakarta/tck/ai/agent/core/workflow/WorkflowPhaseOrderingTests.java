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

import ee.jakarta.tck.ai.agent.core.workflow.agent.PhaseOrderingAgent;
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
 * Behavioral TCK tests for full workflow phase execution order.
 *
 * <p>Verifies that {@code @Trigger}, {@code @Decision}, {@code @Action},
 * and {@code @Outcome} execute in that order when a CDI event is fired.
 *
 * @see PhaseOrderingAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowPhaseOrderingTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "phaseOrdering.war")
                .addClasses(
                        PhaseOrderingAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        PhaseOrderingAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-001",
               section = "Section 3: Workflow Phase Semantics",
               strategy = "Verify the full workflow phase order: trigger -> decision -> action -> outcome")
    public void testFullPhaseExecutionOrder() {
        workflowEvent.fire(new WorkflowEvent("phase-ordering"));

        assertEquals(4, PhaseOrderingAgent.RECORDER.size(),
                "Expected exactly 4 phases to execute");
        assertTrue(PhaseOrderingAgent.RECORDER.wasCalledInOrder(
                        "trigger", "decision", "action", "outcome"),
                "Phases must execute in the declared order");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-002",
               section = "Section 3.1: Trigger",
               strategy = "Verify that @Trigger is the first phase to execute when a CDI event is fired")
    public void testTriggerExecutesFirst() {
        workflowEvent.fire(new WorkflowEvent("trigger-first"));

        assertEquals("trigger", PhaseOrderingAgent.RECORDER.getEntries().get(0).phase(),
                "Trigger must be the first recorded phase");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-003",
               section = "Section 3.4: Outcome",
               strategy = "Verify that @Outcome is the final phase to execute")
    public void testOutcomeExecutesLast() {
        workflowEvent.fire(new WorkflowEvent("outcome-last"));

        int lastIndex = PhaseOrderingAgent.RECORDER.size() - 1;
        assertEquals("outcome", PhaseOrderingAgent.RECORDER.getEntries().get(lastIndex).phase(),
                "Outcome must be the last recorded phase");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-004",
               section = "Section 3: Workflow Phase Semantics",
               strategy = "Verify that each phase is invoked exactly once in a single workflow execution")
    public void testEachPhaseInvokedOnce() {
        workflowEvent.fire(new WorkflowEvent("single-invocation"));

        assertEquals(1, PhaseOrderingAgent.RECORDER.getEntriesForPhase("trigger").size());
        assertEquals(1, PhaseOrderingAgent.RECORDER.getEntriesForPhase("decision").size());
        assertEquals(1, PhaseOrderingAgent.RECORDER.getEntriesForPhase("action").size());
        assertEquals(1, PhaseOrderingAgent.RECORDER.getEntriesForPhase("outcome").size());
    }
}
