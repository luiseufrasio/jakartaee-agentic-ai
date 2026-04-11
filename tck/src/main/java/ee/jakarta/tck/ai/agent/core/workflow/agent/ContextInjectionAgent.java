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
package ee.jakarta.tck.ai.agent.core.workflow.agent;

import ee.jakarta.tck.ai.agent.core.workflow.GreetingService;
import ee.jakarta.tck.ai.agent.core.workflow.WorkflowEvent;
import ee.jakarta.tck.ai.agent.framework.trace.ExecutionTraceRecorder;
import jakarta.ai.agent.Action;
import jakarta.ai.agent.Agent;
import jakarta.ai.agent.Decision;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.ai.agent.Outcome;
import jakarta.ai.agent.Trigger;
import jakarta.enterprise.event.Observes;

/**
 * Verifies injection of the {@link LargeLanguageModel} stub, arbitrary
 * CDI-managed dependencies ({@link GreetingService}), and the triggering
 * event across all workflow phases. The trigger method uses {@code @Observes}.
 */
@Agent(name = "contextInjection")
public class ContextInjectionAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    @Trigger
    public void onEvent(@Observes WorkflowEvent event,
                        LargeLanguageModel llm,
                        GreetingService greeting) {
        String response = llm.query("trigger-prompt");
        String greet = greeting.greet("trigger");
        RECORDER.record("trigger", "onEvent", event, response, greet);
    }

    @Decision
    public boolean evaluate(WorkflowEvent event,
                            LargeLanguageModel llm,
                            GreetingService greeting) {
        String response = llm.query("decision-prompt");
        String greet = greeting.greet("decision");
        RECORDER.record("decision", "evaluate", event, response, greet);
        return true;
    }

    @Action
    public void perform(WorkflowEvent event,
                        LargeLanguageModel llm,
                        GreetingService greeting) {
        String response = llm.query("action-prompt");
        String greet = greeting.greet("action");
        RECORDER.record("action", "perform", event, response, greet);
    }

    @Outcome
    public void conclude(WorkflowEvent event,
                         LargeLanguageModel llm,
                         GreetingService greeting) {
        String response = llm.query("outcome-prompt");
        String greet = greeting.greet("outcome");
        RECORDER.record("outcome", "conclude", event, response, greet);
    }
}
