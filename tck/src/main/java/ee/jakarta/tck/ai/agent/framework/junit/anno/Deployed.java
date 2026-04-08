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
package ee.jakarta.tck.ai.agent.framework.junit.anno;

import ee.jakarta.tck.ai.agent.framework.junit.ext.AssertionExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Profile annotation for behavioral TCK tests that require a CDI container
 * and an agent runtime.
 *
 * <p>These tests verify runtime semantics and actual agent workflows,
 * such as lifecycle method invocation, CDI event firing, and LLM interaction.
 * Annotating a test class with {@code @Deployed} automatically registers the
 * {@link AssertionExtension} and tags the tests with {@code "deployed"} for
 * selective execution.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * @Deployed
 * public class AgentLifecycleTests {
 *     @Assertion(id = "AGENTICAI-LIFECYCLE-001",
 *                section = "Section 3.2: Agent Lifecycle",
 *                strategy = "Verify @Trigger method is invoked on CDI event")
 *     public void testTriggerInvokedOnEvent() {
 *         // behavioral test requiring CDI container
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("deployed")
@ExtendWith(AssertionExtension.class)
public @interface Deployed {
}
