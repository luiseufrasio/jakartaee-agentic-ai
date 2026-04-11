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
 * Verifies that multiple {@code @Action} methods execute in declaration order,
 * that each action can receive prior action results, and that all actions
 * complete before the {@code @Outcome} phase begins.
 *
 * <p>Distinct Java types are used for each action return value to avoid
 * ambiguity during parameter resolution.
 */
@Agent(name = "multiAction")
public class MultiActionAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    @Trigger
    public String onEvent(@Observes WorkflowEvent event) {
        RECORDER.record("trigger", "onEvent", event);
        return "trigger-data";
    }

    @Action
    public Integer firstAction(String triggerResult) {
        RECORDER.record("action", "firstAction", triggerResult);
        return 1;
    }

    @Action
    public Long secondAction(String triggerResult, Integer firstResult) {
        RECORDER.record("action", "secondAction", triggerResult, firstResult);
        return 2L;
    }

    @Action
    public Double thirdAction(String triggerResult, Integer firstResult, Long secondResult) {
        RECORDER.record("action", "thirdAction", triggerResult, firstResult, secondResult);
        return 3.0;
    }

    @Outcome
    public void conclude(String triggerResult, Integer firstResult,
                         Long secondResult, Double thirdResult) {
        RECORDER.record("outcome", "conclude", triggerResult, firstResult, secondResult, thirdResult);
    }
}
