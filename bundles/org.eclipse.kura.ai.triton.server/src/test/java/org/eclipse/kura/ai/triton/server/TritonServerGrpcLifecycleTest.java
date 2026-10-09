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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.kura.core.testutil.TestUtil;
import org.junit.jupiter.api.Test;

import io.grpc.ManagedChannel;

class TritonServerGrpcLifecycleTest extends TritonServerServiceStepDefinitions {

    @Test
    void changedConfigurationReleasesPreviousChannel() throws Exception {
        givenTritonServerServiceRemoteImpl(defaultProperties(), false);
        ManagedChannel previous = currentChannel();

        this.tritonServerService.updated(updatedProperties());

        assertNotSame(previous, currentChannel());
        assertTrue(previous.isShutdown(), "Reconfiguration must release the old channel");
        assertTrue(previous.isTerminated());
        assertFalse(currentChannel().isShutdown());
        assertTrue(this.tritonServerService.isEngineReady());
    }

    @Test
    void invalidConfigurationReleasesPreviousChannel() throws Exception {
        Map<String, Object> properties = defaultProperties();
        givenTritonServerServiceRemoteImpl(properties, false);
        ManagedChannel previous = currentChannel();
        Map<String, Object> invalid = new HashMap<>(properties);
        invalid.put("server.address", "");

        this.tritonServerService.updated(invalid);

        assertTrue(previous.isShutdown(), "An invalid configuration must not retain the old connection");
        assertTrue(previous.isTerminated());
        assertFalse(this.tritonServerService.isEngineReady());
    }

    @Test
    void unchangedConfigurationRetainsLiveChannel() throws Exception {
        Map<String, Object> properties = defaultProperties();
        givenTritonServerServiceRemoteImpl(properties, false);
        ManagedChannel previous = currentChannel();

        this.tritonServerService.updated(properties);

        assertSame(previous, currentChannel());
        assertFalse(previous.isShutdown());
        assertTrue(this.tritonServerService.isEngineReady());
    }

    private ManagedChannel currentChannel() throws Exception {
        return (ManagedChannel) TestUtil.getFieldValue(this.tritonServerService, "grpcChannel");
    }
}
