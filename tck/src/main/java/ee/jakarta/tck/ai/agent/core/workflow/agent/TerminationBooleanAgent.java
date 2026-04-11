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
 * Verifies that a {@code boolean false} return from {@code @Decision}
 * terminates the workflow, skipping all subsequent phases.
 */
@Agent(name = "terminationBoolean")
public class TerminationBooleanAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    @Trigger
    public void onEvent(@Observes WorkflowEvent event) {
        RECORDER.record("trigger", "onEvent", event);
    }

    @Decision
    public boolean evaluate() {
        RECORDER.record("decision", "evaluate");
        return false;
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
