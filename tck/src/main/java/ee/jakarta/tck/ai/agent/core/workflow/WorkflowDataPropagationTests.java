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

import ee.jakarta.tck.ai.agent.core.workflow.agent.DataPropagationAgent;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral TCK tests for data propagation across workflow phases.
 *
 * <p>Verifies that the triggering event, non-void return values from prior
 * phases, and the {@code details} field of a {@link jakarta.ai.agent.Result}
 * are injectable into subsequent phase methods.
 *
 * @see DataPropagationAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowDataPropagationTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "dataPropagation.war")
                .addClasses(
                        DataPropagationAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        DataPropagationAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-014",
               section = "Section 3.1: Trigger - Event Propagation",
               strategy = "Verify the trigger event is available in all subsequent phase methods")
    public void testTriggerEventPropagatedToAllPhases() {
        WorkflowEvent event = new WorkflowEvent("propagate-event");
        workflowEvent.fire(event);

        List<TraceEntry> entries = DataPropagationAgent.RECORDER.getEntries();
        assertEquals(4, entries.size(), "All 4 phases must execute");
        for (TraceEntry entry : entries) {
            assertTrue(Arrays.stream(entry.parameters())
                            .anyMatch(p -> p instanceof WorkflowEvent
                                    && "propagate-event".equals(((WorkflowEvent) p).message())),
                    "Phase " + entry.phase() + " must receive the fired WorkflowEvent");
        }
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-015",
               section = "Section 3.1: Trigger - Return Value Propagation",
               strategy = "Verify a non-void trigger return value is injectable in subsequent phases")
    public void testTriggerReturnValueInjectable() {
        workflowEvent.fire(new WorkflowEvent("propagate-trigger-value"));

        TraceEntry decision = DataPropagationAgent.RECORDER.getEntriesForPhase("decision").get(0);
        TraceEntry action = DataPropagationAgent.RECORDER.getEntriesForPhase("action").get(0);
        TraceEntry outcome = DataPropagationAgent.RECORDER.getEntriesForPhase("outcome").get(0);

        assertTrue(Arrays.asList(decision.parameters()).contains(DataPropagationAgent.TRIGGER_VALUE),
                "Decision must receive the trigger return value");
        assertTrue(Arrays.asList(action.parameters()).contains(DataPropagationAgent.TRIGGER_VALUE),
                "Action must receive the trigger return value");
        assertTrue(Arrays.asList(outcome.parameters()).contains(DataPropagationAgent.TRIGGER_VALUE),
                "Outcome must receive the trigger return value");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-016",
               section = "Section 3.2: Decision - Result Details Propagation",
               strategy = "Verify the Result.details field from @Decision is injectable in @Action")
    public void testDecisionResultDetailsInjectableInAction() {
        workflowEvent.fire(new WorkflowEvent("propagate-details"));

        TraceEntry action = DataPropagationAgent.RECORDER.getEntriesForPhase("action").get(0);
        assertTrue(Arrays.asList(action.parameters()).contains(DataPropagationAgent.DECISION_DETAILS),
                "Action must receive the decision Result.details value");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-017",
               section = "Section 3.3: Action - Return Value Propagation",
               strategy = "Verify a non-void action return value is injectable in @Outcome")
    public void testActionReturnValueInjectableInOutcome() {
        workflowEvent.fire(new WorkflowEvent("propagate-action-value"));

        TraceEntry outcome = DataPropagationAgent.RECORDER.getEntriesForPhase("outcome").get(0);
        assertTrue(Arrays.asList(outcome.parameters()).contains(DataPropagationAgent.ACTION_VALUE),
                "Outcome must receive the action return value");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-018",
               section = "Section 3.4: Outcome - Full State Access",
               strategy = "Verify that @Outcome has access to all prior phase return values")
    public void testOutcomeReceivesAllPriorValues() {
        workflowEvent.fire(new WorkflowEvent("full-state"));

        TraceEntry outcome = DataPropagationAgent.RECORDER.getEntriesForPhase("outcome").get(0);
        Object[] params = outcome.parameters();
        assertNotNull(params, "Outcome parameters must be recorded");
        List<Object> paramList = Arrays.asList(params);
        assertTrue(paramList.contains(DataPropagationAgent.TRIGGER_VALUE),
                "Outcome must receive the trigger value");
        assertTrue(paramList.contains(DataPropagationAgent.DECISION_DETAILS),
                "Outcome must receive the decision details");
        assertTrue(paramList.contains(DataPropagationAgent.ACTION_VALUE),
                "Outcome must receive the action value");
    }
}
