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

import ee.jakarta.tck.ai.agent.core.workflow.agent.ContextInjectionAgent;
import ee.jakarta.tck.ai.agent.framework.junit.anno.Assertion;
import ee.jakarta.tck.ai.agent.framework.junit.anno.Deployed;
import ee.jakarta.tck.ai.agent.framework.stub.StubLargeLanguageModel;
import ee.jakarta.tck.ai.agent.framework.stub.StubLargeLanguageModel.CallRecord;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral TCK tests verifying CDI context injection across workflow phases.
 *
 * <p>Confirms that:
 * <ul>
 *   <li>A {@code LargeLanguageModel} is injectable in every phase method.</li>
 *   <li>Arbitrary CDI-managed dependencies (e.g. {@link GreetingService}) are
 *       injectable in every phase method.</li>
 *   <li>The triggering CDI event is propagated to every phase method.</li>
 * </ul>
 *
 * @see ContextInjectionAgent
 */
@Deployed
@ExtendWith(ArquillianExtension.class)
public class WorkflowContextInjectionTests {

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "contextInjection.war")
                .addClasses(
                        ContextInjectionAgent.class,
                        WorkflowEvent.class,
                        GreetingService.class,
                        StubLargeLanguageModel.class,
                        ExecutionTraceRecorder.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Inject
    private Event<WorkflowEvent> workflowEvent;

    @Inject
    private StubLargeLanguageModel stubLlm;

    @BeforeEach
    public void resetState() {
        ContextInjectionAgent.RECORDER.reset();
        stubLlm.reset();
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-024",
               section = "Section 3: Workflow Phase Semantics - CDI Injection",
               strategy = "Verify a LargeLanguageModel is injectable in every phase method")
    public void testLargeLanguageModelInjectedInAllPhases() {
        workflowEvent.fire(new WorkflowEvent("llm-injection"));

        List<CallRecord> callRecords = stubLlm.getCallRecords();
        assertEquals(4, callRecords.size(),
                "LargeLanguageModel must be invoked once per phase (trigger, decision, action, outcome)");
        assertEquals("trigger-prompt", callRecords.get(0).prompt());
        assertEquals("decision-prompt", callRecords.get(1).prompt());
        assertEquals("action-prompt", callRecords.get(2).prompt());
        assertEquals("outcome-prompt", callRecords.get(3).prompt());
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-025",
               section = "Section 3: Workflow Phase Semantics - CDI Injection",
               strategy = "Verify arbitrary CDI-managed beans are injectable in every phase method")
    public void testCdiBeanInjectedInAllPhases() {
        workflowEvent.fire(new WorkflowEvent("cdi-injection"));

        // Each phase records the greeting produced by the injected GreetingService
        assertTrue(phaseRecordedGreeting("trigger", "Hello, trigger"));
        assertTrue(phaseRecordedGreeting("decision", "Hello, decision"));
        assertTrue(phaseRecordedGreeting("action", "Hello, action"));
        assertTrue(phaseRecordedGreeting("outcome", "Hello, outcome"));
    }

    @Assertion(id = "AGENTICAI-WORKFLOW-026",
               section = "Section 3.1: Trigger - Event Propagation",
               strategy = "Verify the CDI event is propagated to every phase method")
    public void testEventPropagatedToAllPhases() {
        WorkflowEvent event = new WorkflowEvent("event-propagation");
        workflowEvent.fire(event);

        for (TraceEntry entry : ContextInjectionAgent.RECORDER.getEntries()) {
            boolean hasEvent = false;
            for (Object p : entry.parameters()) {
                if (p instanceof WorkflowEvent we && "event-propagation".equals(we.message())) {
                    hasEvent = true;
                    break;
                }
            }
            assertTrue(hasEvent, "Phase " + entry.phase() + " must receive the fired WorkflowEvent");
        }
    }

    private boolean phaseRecordedGreeting(String phase, String greeting) {
        List<TraceEntry> entries = ContextInjectionAgent.RECORDER.getEntriesForPhase(phase);
        if (entries.isEmpty()) {
            return false;
        }
        for (Object p : entries.get(0).parameters()) {
            if (greeting.equals(p)) {
                return true;
            }
        }
        return false;
    }
}
