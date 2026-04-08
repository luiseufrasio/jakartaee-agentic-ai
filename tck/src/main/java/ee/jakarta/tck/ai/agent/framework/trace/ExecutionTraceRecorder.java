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
package ee.jakarta.tck.ai.agent.framework.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records which agent lifecycle methods were called, in what order, and with
 * what parameters.
 *
 * <p>Used by behavioral TCK tests to verify workflow execution semantics.
 * Since {@code WorkflowContext} is not part of the v1 API, this recorder
 * serves as the mechanism for verifying workflow state and method ordering.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * ExecutionTraceRecorder recorder = new ExecutionTraceRecorder();
 *
 * // Inside agent lifecycle methods:
 * recorder.record("trigger", "onCustomerEvent", event);
 * recorder.record("action", "processOrder", order);
 * recorder.record("outcome", "finalizeWorkflow");
 *
 * // In test assertions:
 * assertEquals(3, recorder.getEntries().size());
 * assertEquals("trigger", recorder.getEntries().get(0).phase());
 * assertTrue(recorder.wasCalledInOrder("trigger", "action", "outcome"));
 * }</pre>
 */
public class ExecutionTraceRecorder {

    private final List<TraceEntry> entries = new ArrayList<>();

    /**
     * A single trace entry representing one lifecycle method invocation.
     *
     * @param phase the workflow phase (e.g., "trigger", "decision", "action", "outcome", "handleException")
     * @param methodName the name of the method that was invoked
     * @param parameters the parameters passed to the method
     */
    public record TraceEntry(String phase, String methodName, Object[] parameters) {
    }

    /**
     * Records a lifecycle method invocation.
     *
     * @param phase the workflow phase
     * @param methodName the method name
     * @param parameters the parameters (varargs)
     */
    public void record(String phase, String methodName, Object... parameters) {
        entries.add(new TraceEntry(phase, methodName, parameters));
    }

    /**
     * Returns an unmodifiable list of all recorded trace entries in order.
     *
     * @return the trace entries
     */
    public List<TraceEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns the number of recorded entries.
     *
     * @return the entry count
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns all entries matching the given phase.
     *
     * @param phase the workflow phase to filter by
     * @return list of matching entries
     */
    public List<TraceEntry> getEntriesForPhase(String phase) {
        return entries.stream()
                .filter(e -> e.phase().equals(phase))
                .toList();
    }

    /**
     * Checks whether the given phases were called in the specified order.
     * This does not require the phases to be consecutive — only that each
     * subsequent phase appears after the previous one.
     *
     * @param phases the expected phase order
     * @return {@code true} if the phases appear in order
     */
    public boolean wasCalledInOrder(String... phases) {
        int searchFrom = 0;
        for (String phase : phases) {
            boolean found = false;
            for (int i = searchFrom; i < entries.size(); i++) {
                if (entries.get(i).phase().equals(phase)) {
                    searchFrom = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether a method with the given name was recorded.
     *
     * @param methodName the method name to look for
     * @return {@code true} if at least one entry has the given method name
     */
    public boolean wasMethodCalled(String methodName) {
        return entries.stream().anyMatch(e -> e.methodName().equals(methodName));
    }

    /**
     * Resets the recorder, clearing all entries.
     */
    public void reset() {
        entries.clear();
    }
}
