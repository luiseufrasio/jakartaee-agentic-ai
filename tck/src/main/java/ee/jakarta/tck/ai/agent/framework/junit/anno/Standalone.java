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
 * Profile annotation for standalone TCK tests that do not require a
 * CDI container or agent runtime.
 *
 * <p>These are typically reflection-based structural and signature tests
 * that validate API metadata. Annotating a test class with {@code @Standalone}
 * automatically registers the {@link AssertionExtension} and tags the tests
 * with {@code "standalone"} for selective execution.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * @Standalone
 * public class AgentAnnotationTests {
 *     @Assertion(id = "AGENTICAI-AGENT-001",
 *                strategy = "Verify @Agent annotation exists")
 *     public void testAgentAnnotationExists() {
 *         // reflection-based test
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("standalone")
@ExtendWith(AssertionExtension.class)
public @interface Standalone {
}
