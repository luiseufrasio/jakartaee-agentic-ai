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
 * Verifies short-circuiting behavior across multiple {@code @Decision} methods:
 * decisions execute in declaration order, and when any returns a termination
 * signal, subsequent decisions and phases must not execute.
 */
@Agent(name = "terminationMultiDecision")
public class TerminationMultiDecisionAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    @Trigger
    public void onEvent(@Observes WorkflowEvent event) {
        RECORDER.record("trigger", "onEvent", event);
    }

    @Decision
    public boolean firstDecision() {
        RECORDER.record("decision", "firstDecision");
        return true;
    }

    @Decision
    public boolean secondDecision() {
        RECORDER.record("decision", "secondDecision");
        return false;
    }

    @Decision
    public boolean thirdDecision() {
        RECORDER.record("decision", "thirdDecision");
        return true;
    }

    @Action
    public void perform() {
        RECORDER.record("action", "perform");
    }

    @Outcome
    public void conclude() {
        RECORDER.record("outcome", "conclude");
    }
}
