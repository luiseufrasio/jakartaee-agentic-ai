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

import ee.jakarta.tck.ai.agent.core.workflow.WorkflowEvent;
import ee.jakarta.tck.ai.agent.framework.trace.ExecutionTraceRecorder;
import jakarta.ai.agent.Action;
import jakarta.ai.agent.Agent;
import jakarta.ai.agent.Decision;
import jakarta.ai.agent.Outcome;
import jakarta.ai.agent.Trigger;
import jakarta.enterprise.event.Observes;

/**
 * Verifies the full workflow phase execution order:
 * Trigger &rarr; Decision &rarr; Action &rarr; Outcome.
 */
@Agent(name = "phaseOrdering")
public class PhaseOrderingAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    @Trigger
    public String onEvent(@Observes WorkflowEvent event) {
        RECORDER.record("trigger", "onEvent", event);
        return "trigger-result";
    }

    @Decision
    public boolean evaluate(String triggerResult) {
        RECORDER.record("decision", "evaluate", triggerResult);
        return true;
    }

    @Action
    public Integer perform(String triggerResult) {
        RECORDER.record("action", "perform", triggerResult);
        return 42;
    }

    @Outcome
    public void conclude(String triggerResult, Integer actionResult) {
        RECORDER.record("outcome", "conclude", triggerResult, actionResult);
    }
}
