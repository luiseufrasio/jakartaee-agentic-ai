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
package ee.jakarta.tck.ai.agent.framework.junit.ext;

import ee.jakarta.tck.ai.agent.framework.junit.anno.Assertion;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 5 extension that provides traceability for TCK assertion tests.
 *
 * <p>Logs the start and end of each test execution. On failure, logs the
 * assertion ID and strategy from the {@link Assertion} annotation to aid
 * in mapping failures to specification requirements.
 */
public class AssertionExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger LOGGER = Logger.getLogger(AssertionExtension.class.getName());

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        Method method = context.getRequiredTestMethod();
        Assertion assertion = method.getAnnotation(Assertion.class);
        if (assertion != null) {
            LOGGER.info(() -> String.format("[TCK] START %s - %s#%s",
                    assertion.id(),
                    context.getRequiredTestClass().getSimpleName(),
                    method.getName()));
        } else {
            LOGGER.info(() -> String.format("[TCK] START %s#%s",
                    context.getRequiredTestClass().getSimpleName(),
                    method.getName()));
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Method method = context.getRequiredTestMethod();
        Assertion assertion = method.getAnnotation(Assertion.class);
        boolean failed = context.getExecutionException().isPresent();

        if (failed && assertion != null) {
            String section = assertion.section().isEmpty() ? "N/A" : assertion.section();
            LOGGER.log(Level.SEVERE, () -> String.format(
                    "[TCK] FAIL %s (Section: %s) - Strategy: %s - %s#%s",
                    assertion.id(),
                    section,
                    assertion.strategy(),
                    context.getRequiredTestClass().getSimpleName(),
                    method.getName()));
        } else if (failed) {
            LOGGER.log(Level.SEVERE, () -> String.format("[TCK] FAIL %s#%s",
                    context.getRequiredTestClass().getSimpleName(),
                    method.getName()));
        } else if (assertion != null) {
            LOGGER.info(() -> String.format("[TCK] PASS %s - %s#%s",
                    assertion.id(),
                    context.getRequiredTestClass().getSimpleName(),
                    method.getName()));
        } else {
            LOGGER.info(() -> String.format("[TCK] PASS %s#%s",
                    context.getRequiredTestClass().getSimpleName(),
                    method.getName()));
        }
    }
}
