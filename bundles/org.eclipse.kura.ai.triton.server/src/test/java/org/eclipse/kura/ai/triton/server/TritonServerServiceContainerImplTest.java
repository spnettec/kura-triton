/*******************************************************************************
 * Copyright (c) 2022, 2025 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 ******************************************************************************/
package org.eclipse.kura.ai.triton.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.kura.KuraException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class TritonServerServiceContainerImplTest extends TritonServerServiceStepDefinitions {

    private static final String TRITON_REPOSITORY_PATH = "/fake-repository-path";
    private Map<String, Object> properties = new HashMap<>();
    private Map<String, String> metrics;
    private Map<String, String> expectedMetrics = new HashMap<>();
    private HttpServer httpMetricsServer;

    @Test
    public void isConfigurationValidWorksWithContainerConfiguration() throws IOException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenTritonServerServiceContainerImpl(this.properties, false);

        thenIsConfigurationValidReturns(true);
    }

    @Test
    public void isConfigurationValidWorksWithInvalidImage() throws IOException {
        givenPropertyWith("container.image", null);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenTritonServerServiceContainerImpl(this.properties, false);

        thenIsConfigurationValidReturns(false);
    }

    @Test
    public void isConfigurationValidWorksWithInvalidImageTag() throws IOException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", null);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenTritonServerServiceContainerImpl(this.properties, false);

        thenIsConfigurationValidReturns(false);
    }

    @Test
    public void isConfigurationValidWorksWithInvalidModelRepository() throws IOException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", "");
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenTritonServerServiceContainerImpl(this.properties, false);

        thenIsConfigurationValidReturns(false);
    }

    @Test
    public void isModelEncryptionEnabledWorkWhenPasswordIsNotSet() throws IOException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenTritonServerServiceContainerImpl(this.properties, false);

        thenIsModelEncryptionEnabled(false);
    }

    @Test
    public void isModelEncryptionEnabledWorksWhenPasswordIsSet() throws IOException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenPropertyWith("local.model.repository.password", "keyboards");
        givenTritonServerServiceContainerImpl(this.properties, false);

        thenIsModelEncryptionEnabled(true);
    }

    @Test
    public void shouldGetEmptyMetricsWhenMetricsAreDisabled() throws IOException, KuraException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenPropertyWith("enable.metrics", Boolean.FALSE);
        givenMetricsServerWithResponse("/metrics", TRITON_METRICS_RESPONSE, 200);
        givenTritonServerServiceContainerImpl(this.properties, true);

        whenMetricsAreRetrieved();

        thenMetricsAreEmpty();
    }

    @Test
    public void shouldGetEmptyMetricsWhenResponseIsError() throws IOException, KuraException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenPropertyWith("enable.metrics", Boolean.TRUE);
        givenMetricsServerWithResponse("/metrics", TRITON_METRICS_RESPONSE, 500);
        givenTritonServerServiceContainerImpl(this.properties, false);

        whenMetricsAreRetrieved();

        thenMetricsAreEmpty();
    }

    @Test
    public void shouldGetMetrics() throws IOException, KuraException {
        givenPropertyWith("container.image", TRITON_IMAGE_NAME);
        givenPropertyWith("container.image.tag", TRITON_IMAGE_TAG);
        givenPropertyWith("local.model.repository.path", TRITON_REPOSITORY_PATH);
        givenPropertyWith("server.ports", new Integer[] { 4000, 4001, 4002 });
        givenPropertyWith("enable.metrics", Boolean.TRUE);
        givenMetricsServerWithResponse("/metrics", TRITON_METRICS_RESPONSE, 200);
        givenTritonServerServiceContainerImpl(this.properties, true);
        givenExpectedMetricsAndStats();

        whenMetricsAreRetrieved();

        thenMetricsAre(this.expectedMetrics);
    }

    /*
     * Given
     */
    private void givenPropertyWith(String name, Object value) {
        this.properties.put(name, value);
    }

    private void givenMetricsServerWithResponse(String urlResource, String response, int responseCode)
            throws IOException {
        this.httpMetricsServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.properties.put("server.ports", new Integer[] { 4000, 4001, this.httpMetricsServer.getAddress().getPort() });
        this.httpMetricsServer.createContext(urlResource, new MetricsHandler(response, responseCode));

        this.httpMetricsServer.setExecutor(null);
        this.httpMetricsServer.start();
    }

    private class MetricsHandler implements HttpHandler {

        private String response = "Standard response.";
        private int responseCode = 200;

        public MetricsHandler(String response, int responseCode) {
            this.response = response;
            this.responseCode = responseCode;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = this.response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(this.responseCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    @AfterEach
    public void closeHttpServer() {
        if (this.httpMetricsServer != null) {
            this.httpMetricsServer.stop(0);
        }
    }

    private void givenExpectedMetricsAndStats() {
        String[] gpuItems = TRITON_EXPECTED_METRICS.split(" ");
        this.expectedMetrics.put(gpuItems[0], gpuItems[1]);
        this.expectedMetrics.put(gpuItems[2], gpuItems[3]);

        String[] modelItems = TRITON_EXPECTED_STATS.split(" ");
        this.expectedMetrics.put(modelItems[0], modelItems[1]);
        this.expectedMetrics.put(modelItems[2], modelItems[3]);
    }

    /*
     * When
     */
    private void whenMetricsAreRetrieved() throws KuraException {
        this.metrics = this.tritonServerService.getMetrics();
    }

    /*
     * Then
     */
    private void thenIsConfigurationValidReturns(boolean expectedValue) {
        assertEquals(expectedValue, this.tritonServerService.isConfigurationValid());
    }

    private void thenIsModelEncryptionEnabled(boolean expectedValue) {
        assertEquals(expectedValue, this.tritonServerService.isModelEncryptionEnabled());
    }

    private void thenMetricsAreEmpty() {
        assertNotNull(this.metrics);
        assertTrue(this.metrics.isEmpty());
    }

    private void thenMetricsAre(Map<String, String> expectedValue) {
        assertNotNull(this.metrics);
        assertEquals(expectedValue.keySet(), this.metrics.keySet());
        expectedValue.forEach((key, value) -> assertEquals(com.google.gson.JsonParser.parseString(value),
                com.google.gson.JsonParser.parseString(this.metrics.get(key)), key));
    }
}
