/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.kura.ai.triton.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TritonServerServiceOptionsEqualityTest {

    static Stream<Arguments> resourceOptions() {
        return Stream.of(Arguments.of("container.cpus", 1.78F, 2.0F),
                Arguments.of("container.memory", "7g", "8g"),
                Arguments.of("container.gpus", "all", "2"));
    }

    @ParameterizedTest(name = "{0} compares its value across configuration updates")
    @MethodSource("resourceOptions")
    void equalResourceValuesMustNotTriggerAConfigurationChange(String key, Object value, Object changedValue) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(key, value);
        TritonServerServiceOptions original = new TritonServerServiceOptions(properties);
        TritonServerServiceOptions repeated = new TritonServerServiceOptions(properties);

        assertEquals(original, repeated);
        assertEquals(repeated, original);
        assertEquals(original.hashCode(), repeated.hashCode());

        properties.put(key, changedValue);
        assertNotEquals(original, new TritonServerServiceOptions(properties));
    }
}
