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
import jakarta.ai.agent.Outcome;
import jakarta.ai.agent.Trigger;
import jakarta.enterprise.event.Observes;

/**
 * Verifies that {@code void} returns from {@code @Trigger} and {@code @Action}
 * execute correctly without injecting any value into subsequent phases.
 * The outcome method takes only the trigger event, confirming no phantom
 * values are propagated from void phases.
 */
@Agent(name = "voidPhases")
public class VoidPhasesAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    @Trigger
    public void onEvent(@Observes WorkflowEvent event) {
        RECORDER.record("trigger", "onEvent", event);
    }

    @Action
    public void perform(WorkflowEvent event) {
        RECORDER.record("action", "perform", event);
    }

    @Outcome
    public void conclude(WorkflowEvent event) {
        RECORDER.record("outcome", "conclude", event);
    }
}
