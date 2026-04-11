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
 * Verifies that a workflow can bypass the optional {@code @Decision} phase
 * and move directly from {@code @Trigger} to {@code @Action}.
 */
@Agent(name = "noDecision")
public class NoDecisionAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    @Trigger
    public Integer onEvent(@Observes WorkflowEvent event) {
        RECORDER.record("trigger", "onEvent", event);
        return 42;
    }

    @Action
    public String perform(Integer triggerResult) {
        RECORDER.record("action", "perform", triggerResult);
        return "action-data";
    }

    @Outcome
    public void conclude(Integer triggerResult, String actionResult) {
        RECORDER.record("outcome", "conclude", triggerResult, actionResult);
    }
}
