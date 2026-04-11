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
package ee.jakarta.tck.ai.agent.core.workflow;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A simple CDI bean used in TCK behavioral tests to verify that arbitrary
 * CDI-managed dependencies can be injected into agent lifecycle methods.
 */
@ApplicationScoped
public class GreetingService {

    /**
     * Returns a greeting for the given name.
     *
     * @param name the name to greet
     * @return the greeting string
     */
    public String greet(String name) {
        return "Hello, " + name;
    }
}
