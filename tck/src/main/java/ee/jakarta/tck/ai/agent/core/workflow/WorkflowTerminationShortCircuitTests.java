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

import ee.jakarta.tck.ai.agent.core.workflow.agent.TerminationMultiDecisionAgent;
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
 * Behavioral TCK tests verifying short-circuiting across multiple
 * {@code @Decision} methods: decisions execute in declaration order, and
 * when one returns a termination signal, subsequent decisions and phases
 * must not execute.
 *
 * @see TerminationMultiDecisionAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowTerminationShortCircuitTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "terminationShortCircuit.war")
                .addClasses(
                        TerminationMultiDecisionAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        TerminationMultiDecisionAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-009",
               section = "Section 3.2: Decision - Short-Circuiting",
               strategy = "Verify that the first decision executes and returns true")
    public void testFirstDecisionExecutes() {
        workflowEvent.fire(new WorkflowEvent("short-circuit"));

        assertTrue(TerminationMultiDecisionAgent.RECORDER.wasMethodCalled("firstDecision"),
                "First decision must execute");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-010",
               section = "Section 3.2: Decision - Short-Circuiting",
               strategy = "Verify that the second decision executes and returns false (terminates)")
    public void testSecondDecisionExecutes() {
        workflowEvent.fire(new WorkflowEvent("short-circuit"));

        assertTrue(TerminationMultiDecisionAgent.RECORDER.wasMethodCalled("secondDecision"),
                "Second decision must execute");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-011",
               section = "Section 3.2: Decision - Short-Circuiting",
               strategy = "Verify that the third decision does NOT execute after termination signal")
    public void testThirdDecisionNotExecuted() {
        workflowEvent.fire(new WorkflowEvent("short-circuit"));

        assertFalse(TerminationMultiDecisionAgent.RECORDER.wasMethodCalled("thirdDecision"),
                "Third decision must NOT execute after termination signal");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-012",
               section = "Section 3.2: Decision - Short-Circuiting",
               strategy = "Verify that decisions execute in declaration order")
    public void testDecisionsExecuteInDeclarationOrder() {
        workflowEvent.fire(new WorkflowEvent("short-circuit"));

        assertTrue(TerminationMultiDecisionAgent.RECORDER.wasCalledInOrder(
                        "trigger", "decision", "decision"),
                "Decisions must execute in declaration order after trigger");
        assertEquals("firstDecision",
                TerminationMultiDecisionAgent.RECORDER.getEntries().get(1).methodName());
        assertEquals("secondDecision",
                TerminationMultiDecisionAgent.RECORDER.getEntries().get(2).methodName());
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-013",
               section = "Section 3.2: Decision - Short-Circuiting",
               strategy = "Verify that action and outcome do not execute after short-circuit termination")
    public void testActionAndOutcomeSkipped() {
        workflowEvent.fire(new WorkflowEvent("short-circuit"));

        assertFalse(TerminationMultiDecisionAgent.RECORDER.wasMethodCalled("perform"),
                "Action must NOT execute after short-circuit termination");
        assertFalse(TerminationMultiDecisionAgent.RECORDER.wasMethodCalled("conclude"),
                "Outcome must NOT execute after short-circuit termination");
    }
}
