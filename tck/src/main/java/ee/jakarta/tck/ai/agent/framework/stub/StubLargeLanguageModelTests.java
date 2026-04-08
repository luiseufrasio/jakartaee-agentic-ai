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
package ee.jakarta.tck.ai.agent.framework.stub;

import ee.jakarta.tck.ai.agent.framework.junit.anno.Assertion;
import ee.jakarta.tck.ai.agent.framework.junit.anno.Standalone;
import ee.jakarta.tck.ai.agent.framework.trace.ExecutionTraceRecorder;
import jakarta.ai.agent.LLMException;
import jakarta.ai.agent.LargeLanguageModel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the TCK test infrastructure: {@link StubLargeLanguageModel} and
 * {@link ExecutionTraceRecorder}.
 *
 * <p>These tests ensure that the stub LLM and trace recorder work correctly
 * before they are used in behavioral tests across Issues 2-6.
 */
@Standalone
public class StubLargeLanguageModelTests {

    @Assertion(id = "AGENTICAI-STUB-001",
               section = "TCK Infrastructure",
               strategy = "Verify StubLargeLanguageModel implements LargeLanguageModel")
    public void testStubImplementsInterface() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        assertTrue(stub instanceof LargeLanguageModel,
                "StubLargeLanguageModel must implement LargeLanguageModel");
    }

    @Assertion(id = "AGENTICAI-STUB-002",
               section = "TCK Infrastructure",
               strategy = "Verify stub returns enqueued responses in FIFO order")
    public void testEnqueuedResponses() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        stub.enqueueResponse("first");
        stub.enqueueResponse("second");

        assertEquals("first", stub.query("prompt1"));
        assertEquals("second", stub.query("prompt2"));
    }

    @Assertion(id = "AGENTICAI-STUB-003",
               section = "TCK Infrastructure",
               strategy = "Verify stub tracks calls with prompts and parameters")
    public void testCallTracking() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        stub.enqueueResponse("response");

        stub.query("hello {}", "world");

        assertEquals(1, stub.getCallCount());
        StubLargeLanguageModel.CallRecord record = stub.getCallRecords().get(0);
        assertEquals("hello {}", record.prompt());
        assertArrayEquals(new Object[]{"world"}, record.parameters());
    }

    @Assertion(id = "AGENTICAI-STUB-004",
               section = "TCK Infrastructure",
               strategy = "Verify stub throws configured LLMException")
    public void testLLMExceptionSimulation() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        stub.setExceptionToThrow(new LLMException("service unavailable"));

        LLMException thrown = assertThrows(LLMException.class,
                () -> stub.query("test"));
        assertEquals("service unavailable", thrown.getMessage());
        assertEquals(1, stub.getCallCount(),
                "Call should be recorded even when exception is thrown");
    }

    @Assertion(id = "AGENTICAI-STUB-005",
               section = "TCK Infrastructure",
               strategy = "Verify stub throws configured IllegalArgumentException")
    public void testIllegalArgumentExceptionSimulation() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        stub.setExceptionToThrow(new IllegalArgumentException("invalid prompt"));

        assertThrows(IllegalArgumentException.class,
                () -> stub.query("test"));
    }

    @Assertion(id = "AGENTICAI-STUB-006",
               section = "TCK Infrastructure",
               strategy = "Verify unwrap returns itself for its own type and throws for unknown types")
    public void testUnwrapBehavior() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();

        StubLargeLanguageModel unwrapped = stub.unwrap(StubLargeLanguageModel.class);
        assertSame(stub, unwrapped,
                "unwrap() must return itself for its own type");

        assertThrows(IllegalArgumentException.class,
                () -> stub.unwrap(String.class),
                "unwrap() must throw IllegalArgumentException for unknown types");
    }

    @Assertion(id = "AGENTICAI-STUB-007",
               section = "TCK Infrastructure",
               strategy = "Verify stub supports typed query responses")
    public void testTypedQueryResponse() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        stub.enqueueResponse("typed-value");

        String result = stub.query("prompt", String.class);
        assertEquals("typed-value", result);

        StubLargeLanguageModel.CallRecord record = stub.getCallRecords().get(0);
        assertEquals(String.class, record.resultType());
    }

    @Assertion(id = "AGENTICAI-STUB-008",
               section = "TCK Infrastructure",
               strategy = "Verify stub response function is used when queue is empty")
    public void testResponseFunction() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        stub.setResponseFunction(prompt -> "echo: " + prompt);

        assertEquals("echo: hello", stub.query("hello"));
        assertEquals("echo: world", stub.query("world"));
    }

    @Assertion(id = "AGENTICAI-STUB-009",
               section = "TCK Infrastructure",
               strategy = "Verify stub reset clears all state")
    public void testReset() {
        StubLargeLanguageModel stub = new StubLargeLanguageModel();
        stub.enqueueResponse("response");
        stub.setResponseFunction(p -> "fn");
        stub.query("test");

        stub.reset();

        assertEquals(0, stub.getCallCount());
        assertEquals("stub-response", stub.query("after-reset"),
                "After reset, stub should return default response");
    }

    @Assertion(id = "AGENTICAI-TRACE-001",
               section = "TCK Infrastructure",
               strategy = "Verify ExecutionTraceRecorder records lifecycle phases in order")
    public void testTraceRecorderOrdering() {
        ExecutionTraceRecorder recorder = new ExecutionTraceRecorder();
        recorder.record("trigger", "onEvent", "event-data");
        recorder.record("action", "processData", "data");
        recorder.record("outcome", "finalize");

        assertEquals(3, recorder.size());
        assertTrue(recorder.wasCalledInOrder("trigger", "action", "outcome"));
        assertFalse(recorder.wasCalledInOrder("outcome", "trigger"));
    }

    @Assertion(id = "AGENTICAI-TRACE-002",
               section = "TCK Infrastructure",
               strategy = "Verify ExecutionTraceRecorder filters entries by phase")
    public void testTraceRecorderPhaseFilter() {
        ExecutionTraceRecorder recorder = new ExecutionTraceRecorder();
        recorder.record("decision", "checkA");
        recorder.record("action", "doWork");
        recorder.record("decision", "checkB");

        assertEquals(2, recorder.getEntriesForPhase("decision").size());
        assertEquals(1, recorder.getEntriesForPhase("action").size());
        assertEquals(0, recorder.getEntriesForPhase("trigger").size());
    }

    @Assertion(id = "AGENTICAI-TRACE-003",
               section = "TCK Infrastructure",
               strategy = "Verify ExecutionTraceRecorder tracks method names")
    public void testTraceRecorderMethodTracking() {
        ExecutionTraceRecorder recorder = new ExecutionTraceRecorder();
        recorder.record("action", "processOrder", "order-123");

        assertTrue(recorder.wasMethodCalled("processOrder"));
        assertFalse(recorder.wasMethodCalled("nonExistent"));
    }
}
