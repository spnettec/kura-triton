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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

class TritonCopiedConfigurationTest {

    @Test
    void copiedConfigAdminPortValuesCompareEqualAndWorkAsAHashKey() {
        var original = new TritonServerServiceOptions(configuration());
        var copied = new TritonServerServiceOptions(configuration());
        assertEquals(original, copied);
        assertEquals(copied, original);
        assertEquals(original.hashCode(), copied.hashCode());
        assertTrue(Set.of(original).contains(copied));
    }

    @Test
    void equalCopiedPortsRetainTheActualLiveGrpcChannel() throws Exception {
        var remote = new TritonServerServiceRemoteImpl();
        ManagedChannel original = null;
        try {
            remote.activate(configuration());
            original = channel(remote);
            assertFalse(original.isShutdown());
            remote.updated(configuration());
            assertSame(original, channel(remote), "A copied port array must not restart the remote connection");
            assertFalse(original.isShutdown());
        } finally {
            remote.deactivate();
            if (original != null) {
                original.shutdownNow();
                assertTrue(original.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void changedPortsAddressAndMissingPropertiesRemainDifferent() {
        var original = new TritonServerServiceOptions(configuration());
        var changed = configuration();
        changed.put("server.ports", new Integer[] { 4100, 4101, 4102 });
        assertNotEquals(original, new TritonServerServiceOptions(changed));
        changed = configuration();
        changed.put("server.address", "localhost");
        assertNotEquals(original, new TritonServerServiceOptions(changed));
        changed = configuration();
        changed.remove("models");
        assertNotEquals(original, new TritonServerServiceOptions(changed));
    }

    private static Map<String, Object> configuration() {
        return new HashMap<>(Map.of("server.address", "127.0.0.1", "server.ports", new Integer[] { 4000, 4001, 4002 },
                "models", "", "timeout", 1));
    }

    private static ManagedChannel channel(TritonServerServiceRemoteImpl remote) throws Exception {
        Field field = TritonServerServiceAbs.class.getDeclaredField("grpcChannel");
        field.setAccessible(true);
        return (ManagedChannel) field.get(remote);
    }
}
