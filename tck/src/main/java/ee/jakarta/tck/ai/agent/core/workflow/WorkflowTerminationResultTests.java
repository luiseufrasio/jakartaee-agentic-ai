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

import ee.jakarta.tck.ai.agent.core.workflow.agent.TerminationResultAgent;
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

/**
 * Behavioral TCK tests verifying that a {@code Result} with
 * {@code success=false} returned from {@code @Decision} terminates the
 * workflow.
 *
 * @see TerminationResultAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowTerminationResultTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "terminationResult.war")
                .addClasses(
                        TerminationResultAgent.class,
                        WorkflowEvent.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @BeforeEach
    public void resetRecorder() {
        TerminationResultAgent.RECORDER.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-007",
               section = "Section 3.2: Decision - Conditional Execution",
               strategy = "Verify that Result(success=false) from @Decision terminates the workflow")
    public void testResultFalseTerminatesWorkflow() {
        workflowEvent.fire(new WorkflowEvent("result-termination"));

        assertEquals(2, TerminationResultAgent.RECORDER.size(),
                "Only trigger and decision should execute when Result.success is false");
        assertFalse(TerminationResultAgent.RECORDER.wasMethodCalled("perform"),
                "Action must NOT execute when decision returns Result(false, ...)");
        assertFalse(TerminationResultAgent.RECORDER.wasMethodCalled("conclude"),
                "Outcome must NOT execute when decision returns Result(false, ...)");
    }
}
