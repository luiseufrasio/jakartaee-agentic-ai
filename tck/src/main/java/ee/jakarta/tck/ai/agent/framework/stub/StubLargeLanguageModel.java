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

import jakarta.ai.agent.LLMException;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;

/**
 * A test stub implementation of {@link LargeLanguageModel} for TCK behavioral tests.
 *
 * <p>This CDI bean can be injected into test agents and provides:
 * <ul>
 *   <li>Configurable responses (predefined strings/objects for given prompts)</li>
 *   <li>Call tracking (prompts received, parameters passed) for verification</li>
 *   <li>Exception simulation ({@link LLMException} and {@link IllegalArgumentException})</li>
 *   <li>Proper {@link #unwrap(Class)} behavior</li>
 * </ul>
 *
 * <p>This stub makes the TCK portable by removing the dependency on external LLM providers.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * StubLargeLanguageModel stub = new StubLargeLanguageModel();
 * stub.enqueueResponse("Hello, World!");
 * stub.enqueueResponse("Second response");
 *
 * String result = stub.query("Say hello");
 * assertEquals("Hello, World!", result);
 * assertEquals(1, stub.getCallRecords().size());
 * assertEquals("Say hello", stub.getCallRecords().get(0).prompt());
 * }</pre>
 */
@ApplicationScoped
public class StubLargeLanguageModel implements LargeLanguageModel {

    private final Queue<Object> responseQueue = new LinkedList<>();
    private final List<CallRecord> callRecords = new ArrayList<>();
    private Function<String, String> responseFunction;
    private RuntimeException exceptionToThrow;

    /**
     * Records a single call to the stub LLM.
     *
     * @param prompt the prompt sent to the LLM
     * @param resultType the requested result type, or {@code null} for String queries
     * @param parameters the parameters passed to the query, or empty array if none
     */
    public record CallRecord(String prompt, Class<?> resultType, Object[] parameters) {
    }

    /**
     * Enqueues a response to be returned by the next {@code query()} call.
     * Responses are returned in FIFO order. If the queue is exhausted and no
     * response function is set, a default response is returned.
     *
     * @param response the response to enqueue
     */
    public void enqueueResponse(Object response) {
        responseQueue.add(response);
    }

    /**
     * Sets a function that generates responses dynamically based on the prompt.
     * This function is used when the response queue is empty.
     *
     * @param function the response function, or {@code null} to remove
     */
    public void setResponseFunction(Function<String, String> function) {
        this.responseFunction = function;
    }

    /**
     * Configures the stub to throw the given exception on the next {@code query()} call.
     * The exception is thrown before any response processing. Set to {@code null} to clear.
     *
     * <p>Supports {@link LLMException} and {@link IllegalArgumentException}.
     *
     * @param exception the exception to throw, or {@code null} to clear
     */
    public void setExceptionToThrow(RuntimeException exception) {
        this.exceptionToThrow = exception;
    }

    /**
     * Returns an unmodifiable list of all call records.
     *
     * @return the call records
     */
    public List<CallRecord> getCallRecords() {
        return Collections.unmodifiableList(callRecords);
    }

    /**
     * Returns the number of times any {@code query()} method has been called.
     *
     * @return the call count
     */
    public int getCallCount() {
        return callRecords.size();
    }

    /**
     * Resets the stub to its initial state: clears all queued responses,
     * call records, response function, and exception configuration.
     */
    public void reset() {
        responseQueue.clear();
        callRecords.clear();
        responseFunction = null;
        exceptionToThrow = null;
    }

    @Override
    public String query(String prompt) {
        callRecords.add(new CallRecord(prompt, null, new Object[0]));
        throwIfConfigured();
        return resolveResponse(prompt);
    }

    @Override
    public <T> T query(String prompt, Class<T> resultType) {
        callRecords.add(new CallRecord(prompt, resultType, new Object[0]));
        throwIfConfigured();
        return resultType.cast(resolveResponseObject(prompt));
    }

    @Override
    public String query(String prompt, Object... parameters) {
        callRecords.add(new CallRecord(prompt, null, parameters != null ? parameters : new Object[0]));
        throwIfConfigured();
        return resolveResponse(prompt);
    }

    @Override
    public <T> T query(String prompt, Class<T> resultType, Object... parameters) {
        callRecords.add(new CallRecord(prompt, resultType, parameters != null ? parameters : new Object[0]));
        throwIfConfigured();
        return resultType.cast(resolveResponseObject(prompt));
    }

    @Override
    public <T> T unwrap(Class<T> implClass) {
        if (implClass.isInstance(this)) {
            return implClass.cast(this);
        }
        throw new IllegalArgumentException(
                "Cannot unwrap to " + implClass.getName()
                        + "; this stub is of type " + getClass().getName());
    }

    private void throwIfConfigured() {
        if (exceptionToThrow != null) {
            RuntimeException e = exceptionToThrow;
            exceptionToThrow = null;
            throw e;
        }
    }

    private String resolveResponse(String prompt) {
        Object response = resolveResponseObject(prompt);
        return response != null ? response.toString() : null;
    }

    private Object resolveResponseObject(String prompt) {
        if (!responseQueue.isEmpty()) {
            return responseQueue.poll();
        }
        if (responseFunction != null) {
            return responseFunction.apply(prompt);
        }
        return "stub-response";
    }
}
