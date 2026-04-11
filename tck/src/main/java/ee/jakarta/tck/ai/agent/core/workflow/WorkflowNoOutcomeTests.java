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

import ee.jakarta.tck.ai.agent.core.workflow.agent.NoOutcomeAgent;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral TCK tests verifying that {@code @Outcome} is an optional phase.
 * A workflow lacking any {@code @Outcome} method must complete normally
 * after its last {@code @Action} executes.
 *
 * @see NoOutcomeAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowNoOutcomeTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "noOutcome.war")
                .addClasses(
                        NoOutcomeAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        NoOutcomeAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-032",
               section = "Section 3.4: Outcome - Optional Phase",
               strategy = "Verify a workflow without @Outcome completes normally after its last @Action")
    public void testWorkflowWithoutOutcome() {
        workflowEvent.fire(new WorkflowEvent("no-outcome"));

        assertEquals(3, NoOutcomeAgent.RECORDER.size(),
                "Only trigger, decision, and action must execute");
        assertTrue(NoOutcomeAgent.RECORDER.wasCalledInOrder("trigger", "decision", "action"),
                "Phases must execute in order: trigger, decision, action");
        assertFalse(NoOutcomeAgent.RECORDER.getEntriesForPhase("outcome").size() > 0,
                "No outcome phase must be recorded");
    }
}
