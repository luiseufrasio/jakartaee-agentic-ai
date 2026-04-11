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
import jakarta.ai.agent.Result;
import jakarta.ai.agent.Trigger;
import jakarta.enterprise.event.Observes;

/**
 * Verifies data propagation across workflow phases:
 * <ul>
 *   <li>The trigger event is available in all subsequent phases.</li>
 *   <li>A non-void trigger return value is injectable in subsequent phases.</li>
 *   <li>The {@code details} field from a {@link Result} return by a decision
 *       is injectable in the following action.</li>
 *   <li>A non-void action return value is available in the outcome.</li>
 * </ul>
 *
 * <p>Distinct Java types are used for each phase return value to avoid
 * ambiguity during parameter resolution.
 */
@Agent(name = "dataPropagation")
public class DataPropagationAgent {

    public static final ExecutionTraceRecorder RECORDER = new ExecutionTraceRecorder();

    public static final Integer TRIGGER_VALUE = 42;
    public static final String DECISION_DETAILS = "decision-details";
    public static final Long ACTION_VALUE = 100L;

    @Trigger
    public Integer onEvent(@Observes WorkflowEvent event) {
        RECORDER.record("trigger", "onEvent", event);
        return TRIGGER_VALUE;
    }

    @Decision
    public Result evaluate(WorkflowEvent event, Integer triggerResult) {
        RECORDER.record("decision", "evaluate", event, triggerResult);
        return new Result(true, DECISION_DETAILS);
    }

    @Action
    public Long perform(WorkflowEvent event, Integer triggerResult, String decisionDetails) {
        RECORDER.record("action", "perform", event, triggerResult, decisionDetails);
        return ACTION_VALUE;
    }

    @Outcome
    public void conclude(WorkflowEvent event, Integer triggerResult,
                         String decisionDetails, Long actionResult) {
        RECORDER.record("outcome", "conclude", event, triggerResult, decisionDetails, actionResult);
    }
}
