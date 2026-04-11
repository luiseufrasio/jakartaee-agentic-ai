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

import ee.jakarta.tck.ai.agent.core.workflow.agent.TerminationBooleanAgent;
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
 * Behavioral TCK tests verifying that a {@code boolean false} return from
 * {@code @Decision} terminates the workflow.
 *
 * @see TerminationBooleanAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowTerminationBooleanTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "terminationBoolean.war")
                .addClasses(
                        TerminationBooleanAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        TerminationBooleanAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-005",
               section = "Section 3.2: Decision - Conditional Execution",
               strategy = "Verify that boolean false from @Decision terminates the workflow")
    public void testBooleanFalseTerminatesWorkflow() {
        workflowEvent.fire(new WorkflowEvent("boolean-termination"));

        assertTrue(TerminationBooleanAgent.RECORDER.wasCalledInOrder("trigger", "decision"),
                "Trigger and decision must execute before termination");
        assertFalse(TerminationBooleanAgent.RECORDER.wasMethodCalled("perform"),
                "Action must NOT execute when decision returns false");
        assertFalse(TerminationBooleanAgent.RECORDER.wasMethodCalled("conclude"),
                "Outcome must NOT execute when decision returns false");
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-006",
               section = "Section 3.2: Decision - Conditional Execution",
               strategy = "Verify only trigger and decision execute when boolean decision returns false")
    public void testOnlyTriggerAndDecisionExecute() {
        workflowEvent.fire(new WorkflowEvent("boolean-termination"));

        assertEquals(2, TerminationBooleanAgent.RECORDER.size(),
                "Only trigger and decision should be recorded");
    }
}
