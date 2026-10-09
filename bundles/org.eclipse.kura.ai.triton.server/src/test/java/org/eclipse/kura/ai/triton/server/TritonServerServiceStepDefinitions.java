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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.KuraRuntimeException;
import org.eclipse.kura.ai.inference.ModelInfo;
import org.eclipse.kura.ai.inference.Tensor;
import org.eclipse.kura.ai.inference.TensorDescriptor;
import org.eclipse.kura.container.orchestration.ContainerOrchestrationService;
import org.eclipse.kura.container.orchestration.ImageInstanceDescriptor;
import org.eclipse.kura.crypto.CryptoService;
import org.eclipse.kura.executor.Command;
import org.eclipse.kura.executor.CommandExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import org.mockito.MockedStatic;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.ByteString;

import inference.GRPCInferenceServiceGrpc;
import inference.GrpcService.InferBatchStatistics;
import inference.GrpcService.InferStatistics;
import inference.GrpcService.InferTensorContents;
import inference.GrpcService.ModelInferRequest;
import inference.GrpcService.ModelInferResponse;
import inference.GrpcService.ModelInferResponse.InferOutputTensor;
import inference.GrpcService.ModelMetadataRequest;
import inference.GrpcService.ModelMetadataResponse;
import inference.GrpcService.ModelMetadataResponse.TensorMetadata;
import inference.GrpcService.ModelStatistics;
import inference.GrpcService.ModelStatisticsRequest;
import inference.GrpcService.ModelStatisticsResponse;
import inference.GrpcService.RepositoryIndexRequest;
import inference.GrpcService.RepositoryIndexResponse;
import inference.GrpcService.RepositoryIndexResponse.ModelIndex;
import inference.GrpcService.RepositoryModelUnloadRequest;
import inference.GrpcService.RepositoryModelUnloadResponse;
import inference.GrpcService.ServerLiveRequest;
import inference.GrpcService.ServerLiveResponse;
import inference.GrpcService.StatisticDuration;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;


public abstract class TritonServerServiceStepDefinitions extends TritonServerServiceConstants {

    protected static final String TRITON_IMAGE_NAME = "tritonserver";
    protected static final String TRITON_IMAGE_TAG = "latest";

    protected TritonServerServiceAbs tritonServerService;
    protected boolean methodCalled;
    protected boolean exceptionCaught;
    protected Optional<ModelInfo> modelInfo;

    private List<String> tritonModelRepoStub;

    private Command startTritonServerCmd = new Command(new String[] { "tritonserver",
            "--model-repository=/fake-repository-path", "--backend-directory=/fake-backends-path", "--http-port=4001",
            "--grpc-port=4002", "--metrics-port=4003", "--model-control-mode=explicit", "--allow-metrics=true",
            "--allow-gpu-metrics=true", "2>&1", "|", "systemd-cat", "-t tritonserver", "-p info" });

    public TritonServerServiceStepDefinitions() {
        this.startTritonServerCmd.setExecuteInAShell(true);
        this.tritonModelRepoStub = Arrays.asList("myModel");
        this.modelInfo = Optional.empty();
    }

    private final List<ManagedChannel> channels = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();
    private MockedStatic<ManagedChannelBuilder> channelFactory;

    private void prepareGrpcBoundary(boolean metricsEnabled) throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        this.servers.add(InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(createGRPCMock(this.tritonModelRepoStub, metricsEnabled)).build().start());
        this.channelFactory = mockStatic(ManagedChannelBuilder.class);
        this.channelFactory.when(() -> ManagedChannelBuilder.forAddress(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    ManagedChannelBuilder<?> builder = spy(InProcessChannelBuilder.forName(serverName).directExecutor());
                    doAnswer(build -> {
                        ManagedChannel channel = (ManagedChannel) build.callRealMethod();
                        this.channels.add(channel);
                        return channel;
                    }).when(builder).build();
                    return builder;
                });
        // Invalid configurations still need a stub for explicit inference API scenarios.
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        this.channels.add(channel);
        this.tritonServerService.setGrpcStub(GRPCInferenceServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void closeOwnedResources() throws InterruptedException {
        try {
            if (this.tritonServerService != null) {
                this.tritonServerService.deactivate();
            }
        } finally {
            this.channels.forEach(ManagedChannel::shutdownNow);
            this.servers.forEach(Server::shutdownNow);
            try {
                for (ManagedChannel channel : this.channels) {
                    assertTrue(channel.awaitTermination(2, TimeUnit.SECONDS));
                }
                for (Server server : this.servers) {
                    assertTrue(server.awaitTermination(2, TimeUnit.SECONDS));
                }
            } finally {
                if (this.channelFactory != null) {
                    this.channelFactory.close();
                }
            }
        }
    }

    protected List<String> modelsFound = new ArrayList<>();
    private List<Tensor> tensorList = new ArrayList<>();
    private boolean isEngineReady;
    private ModelInferRequest inferenceRequest;
    private CommandExecutorService ces;
    private CryptoService cry;
    private ContainerOrchestrationService orc;

    protected void givenTritonServerServiceRemoteImpl(Map<String, Object> properties, boolean metricsEnabled)
            throws IOException {
        this.tritonServerService = createTritonServerServiceRemoteImpl(properties, tritonModelRepoStub, true,
                metricsEnabled);
    }

    protected void givenTritonServerServiceNativeImpl(Map<String, Object> properties, boolean metricsEnabled)
            throws IOException {
        this.tritonServerService = createTritonServerServiceNativeImpl(properties, tritonModelRepoStub, true,
                metricsEnabled);
    }

    protected void givenTritonServerServiceContainerImpl(Map<String, Object> properties, boolean metricsEnabled)
            throws IOException {
        this.tritonServerService = createTritonServerServiceContainerImpl(properties, tritonModelRepoStub, true,
                metricsEnabled);
    }

    protected void givenTritonServerServiceNativeImplNotActive() throws IOException {
        this.tritonServerService = createTritonServerServiceNativeImpl(new HashMap<>(), tritonModelRepoStub, false,
                false);
    }

    protected void whenLoadModel(String modelName) {
        try {
            this.tritonServerService.loadModel(modelName, Optional.empty());
        } catch (KuraException e) {
            this.exceptionCaught = true;
        }
    }

    protected void whenGetModelLoadState(String modelName) {
        try {
            this.tritonServerService.isModelLoaded(modelName);
        } catch (KuraException e) {
            this.exceptionCaught = true;
        }
    }

    protected void whenUnloadModel(String modelName) {
        try {
            this.tritonServerService.unloadModel(modelName);
        } catch (KuraException e) {
            this.exceptionCaught = true;
        }
    }

    protected void whenGetModelNames() {
        try {
            this.modelsFound = this.tritonServerService.getModelNames();
        } catch (KuraException e) {
            this.exceptionCaught = true;
        }
    }

    protected void whenGetModelInfo(String modelName) {
        try {
            this.modelInfo = this.tritonServerService.getModelInfo(modelName);
        } catch (KuraException e) {
            this.exceptionCaught = true;
        }
    }

    protected void whenInferData(ModelInfo modelInfo, List<Tensor> inputData) {
        try {
            this.tensorList = this.tritonServerService.infer(modelInfo, inputData);
        } catch (KuraException e) {
            this.exceptionCaught = true;
        }
    }

    protected void whenAskingIfEngineIsReady() {
        this.isEngineReady = this.tritonServerService.isEngineReady();
    }

    protected void whenTritonServerIsActivated(Map<String, Object> properties) {
        try {
            this.tritonServerService.activate(properties);
        } catch (KuraRuntimeException kre) {
            this.exceptionCaught = true;
        }
    }

    protected void whenDeactivateIsInvokedOnTritonServer() {
        try {
            this.tritonServerService.deactivate();
        } catch (KuraRuntimeException kre) {
            this.exceptionCaught = true;
        }
    }

    protected void whenUpdatedIsInvokedOnTritonServer(Map<String, Object> properties) {
        try {
            this.tritonServerService.updated(properties);
        } catch (KuraRuntimeException kre) {
            this.exceptionCaught = true;
        }
    }

    protected void thenExceptionIsCaught() {
        assertTrue(this.exceptionCaught);
    }

    protected void thenNoExceptionIsCaught() {
        assertFalse(this.exceptionCaught);
    }

    protected void thenModelIsLoaded() {
        assertFalse(this.exceptionCaught);
        assertTrue(this.methodCalled);
    }

    protected void thenModelIsUnLoaded() {
        assertFalse(this.exceptionCaught);
        assertTrue(this.methodCalled);
    }

    protected void thenListIsNotEmpty() {
        assertFalse(this.exceptionCaught);
        assertTrue(this.methodCalled);
        assertFalse(this.modelsFound.isEmpty());
    }

    protected void thenModelInfoExists() {
        assertFalse(this.exceptionCaught);
        assertTrue(this.methodCalled);
        assertTrue(this.modelInfo.isPresent());
    }

    protected void thenTensorsAreReturned() {
        assertFalse(this.exceptionCaught);
        assertTrue(this.methodCalled);
        assertEquals("name", this.inferenceRequest.getModelName());
        assertEquals(1, this.inferenceRequest.getInputsCount());
        assertEquals("input", this.inferenceRequest.getInputs(0).getName());
        assertEquals(Arrays.asList(3L), this.inferenceRequest.getInputs(0).getShapeList());
        assertEquals(Arrays.asList(3.45d, 7.34d, 88.887d),
                this.inferenceRequest.getInputs(0).getContents().getFp64ContentsList());
        assertEquals(1L, this.inferenceRequest.getInputs(0).getParametersOrThrow("param1").getInt64Param());
        assertEquals(8, this.inferenceRequest.getOutputsCount());
        assertEquals(8, this.tensorList.size());
        this.tensorList.forEach(tensor -> assertEquals(Arrays.asList(1L), tensor.getDescriptor().getShape()));
        assertEquals(Arrays.asList(34.76d), this.tensorList.get(0).getData(Double.class).orElseThrow());
        assertEquals(Arrays.asList(true), this.tensorList.get(1).getData(Boolean.class).orElseThrow());
        assertEquals(Arrays.asList((byte) 10, (byte) 20, (byte) -10, (byte) -20),
                this.tensorList.get(2).getData(Byte.class).orElseThrow());
        assertEquals(Arrays.asList(134.76f), this.tensorList.get(3).getData(Float.class).orElseThrow());
        assertEquals(Arrays.asList(56436L), this.tensorList.get(4).getData(Long.class).orElseThrow());
        assertEquals(Arrays.asList(45465), this.tensorList.get(5).getData(Integer.class).orElseThrow());
        assertEquals(Arrays.asList(536456L), this.tensorList.get(6).getData(Long.class).orElseThrow());
        assertEquals(Arrays.asList(53645), this.tensorList.get(7).getData(Integer.class).orElseThrow());
    }

    @SuppressWarnings("unchecked")
    protected void thenTritonStartServerCommandIsExecuted() {
        verify(this.ces, timeout(2000)).execute(eq(this.startTritonServerCmd), any(Consumer.class));
    }

    protected Map<String, Object> defaultProperties() {

        Map<String, Object> properties = new HashMap<>();

        properties.put("server.address", "localhost");
        properties.put("server.ports", new Integer[] { 4000, 4001, 4002 });

        return properties;
    }

    protected Map<String, Object> updatedProperties() {

        Map<String, Object> properties = new HashMap<>();

        properties.put("server.address", "localhost");
        properties.put("server.ports", new Integer[] { 4001, 4002, 4003 });

        return properties;
    }

    protected Map<String, Object> invalidProperties() {
        Map<String, Object> properties = new HashMap<>();

        properties.put("server.ports", new Integer[] { 4000, 4001 });

        return properties;
    }

    protected Map<String, Object> enableLocalServerProperties() {
        Map<String, Object> properties = new HashMap<>();

        properties.put("server.ports", new Integer[] { 4001, 4002, 4003 });
        properties.put("local.backends.path", "/fake-backends-path");
        properties.put("local.model.repository.path", "/fake-repository-path");

        return properties;
    }

    protected void thenEngineIsReady() {
        assertTrue(this.isEngineReady);
    }

    protected ModelInfo exampleModel() {
        List<TensorDescriptor> outputs = new ArrayList<>();
        String[] types = { "FP64", "BOOL", "BYTES", "FP32", "INT64", "INT32", "UINT64", "UINT32" };
        for (int i = 0; i < types.length; i++) {
            outputs.add(TensorDescriptor.builder("name" + (i + 1), types[i], Arrays.asList(1L)).build());
        }
        return ModelInfo.builder("name").platform("platform").version("version")
                .addAllInputDescriptor(Arrays.asList(TensorDescriptor.builder("input", "FP64", Arrays.asList(3L)).build()))
                .addAllOutputDescriptor(outputs).build();
    }

    protected List<Tensor> exampleInputData() {
        List<Tensor> tensors = new ArrayList<>();

        List<Long> shape = new ArrayList<>();
        shape.add(3L);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("param1", 1l);

        List<Double> data = Arrays.asList(3.45, 7.34, 88.887);

        tensors.add(
                new Tensor(Double.class, new TensorDescriptor("input", "FP64", Optional.empty(), shape, params), data));

        return tensors;
    }

    private TritonServerServiceAbs createTritonServerServiceNativeImpl(Map<String, Object> properties,
            List<String> tritonModelRepoStub, boolean activate, boolean metricsEnabled) throws IOException {

        TritonServerServiceAbs tritonServerServiceImpl = new TritonServerServiceNativeImpl();
        this.tritonServerService = tritonServerServiceImpl;
        prepareGrpcBoundary(metricsEnabled);

        this.ces = mock(CommandExecutorService.class);
        when(ces.isRunning(new String[] { "tritonserver" })).thenReturn(false);

        tritonServerServiceImpl.setCommandExecutorService(ces);

        this.cry = mock(CryptoService.class);
        tritonServerServiceImpl.setCryptoService(cry);

        if (activate) {
            tritonServerServiceImpl.activate(properties);
        }


        return tritonServerServiceImpl;
    }

    private TritonServerServiceAbs createTritonServerServiceContainerImpl(Map<String, Object> properties,
            List<String> tritonModelRepoStub, boolean activate, boolean metricsEnabled) throws IOException {

        TritonServerServiceAbs tritonServerServiceImpl = new TritonServerServiceContainerImpl();
        this.tritonServerService = tritonServerServiceImpl;
        prepareGrpcBoundary(metricsEnabled);

        this.orc = mock(ContainerOrchestrationService.class);
        setTritonDockerImageAsAvailable();
        setTritonDockerContainerAsNotRunning();
        tritonServerServiceImpl.setContainerOrchestrationService(orc);

        this.cry = mock(CryptoService.class);
        tritonServerServiceImpl.setCryptoService(cry);

        if (activate) {
            tritonServerServiceImpl.activate(properties);
        }


        return tritonServerServiceImpl;
    }

    private void setTritonDockerImageAsAvailable() {
        ImageInstanceDescriptor imageDescriptor = mock(ImageInstanceDescriptor.class);
        when(imageDescriptor.getImageName()).thenReturn(TRITON_IMAGE_NAME);
        when(imageDescriptor.getImageTag()).thenReturn(TRITON_IMAGE_TAG);
        when(this.orc.listImageInstanceDescriptors()).thenReturn(Arrays.asList(imageDescriptor));
    }

    private void setTritonDockerContainerAsNotRunning() {
        when(this.orc.listContainerDescriptors()).thenReturn(Arrays.asList());
    }

    private TritonServerServiceAbs createTritonServerServiceRemoteImpl(Map<String, Object> properties,
            List<String> tritonModelRepoStub, boolean activate, boolean metricsEnabled) throws IOException {

        TritonServerServiceAbs tritonServerServiceImpl = new TritonServerServiceRemoteImpl();
        this.tritonServerService = tritonServerServiceImpl;
        prepareGrpcBoundary(metricsEnabled);

        this.ces = mock(CommandExecutorService.class);
        tritonServerServiceImpl.setCommandExecutorService(ces);

        this.cry = mock(CryptoService.class);
        tritonServerServiceImpl.setCryptoService(cry);

        if (activate) {
            tritonServerServiceImpl.activate(properties);
        }


        return tritonServerServiceImpl;
    }

    private GRPCInferenceServiceGrpc.GRPCInferenceServiceImplBase createGRPCMock(List<String> tritonModelRepoStub,
            boolean metricsEnabled) {
        return mock(GRPCInferenceServiceGrpc.GRPCInferenceServiceImplBase.class,
                delegatesTo(new GRPCInferenceServiceGrpc.GRPCInferenceServiceImplBase() {

                    @Override
                    public void repositoryModelLoad(inference.GrpcService.RepositoryModelLoadRequest request,
                            io.grpc.stub.StreamObserver<inference.GrpcService.RepositoryModelLoadResponse> responseObserver) {
                        TritonServerServiceStepDefinitions.this.methodCalled = true;
                        if (!tritonModelRepoStub.contains(request.getModelName())) {
                            responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT));
                        } else {
                            responseObserver
                                    .onNext(inference.GrpcService.RepositoryModelLoadResponse.getDefaultInstance());
                            responseObserver.onCompleted();
                        }
                    }

                    @Override
                    public void repositoryModelUnload(RepositoryModelUnloadRequest request,
                            StreamObserver<RepositoryModelUnloadResponse> responseObserver) {
                        TritonServerServiceStepDefinitions.this.methodCalled = true;
                        if (!tritonModelRepoStub.contains(request.getModelName())) {
                            responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT));
                        } else {
                            responseObserver
                                    .onNext(inference.GrpcService.RepositoryModelUnloadResponse.getDefaultInstance());
                            responseObserver.onCompleted();
                        }
                    }

                    @Override
                    public void repositoryIndex(RepositoryIndexRequest request,
                            StreamObserver<RepositoryIndexResponse> responseObserver) {
                        TritonServerServiceStepDefinitions.this.methodCalled = true;

                        ModelIndex modelIndex = ModelIndex.newBuilder().setName("myModel").build();
                        RepositoryIndexResponse response = RepositoryIndexResponse.newBuilder().addModels(modelIndex)
                                .build();
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void modelMetadata(ModelMetadataRequest request,
                            StreamObserver<ModelMetadataResponse> responseObserver) {

                        TritonServerServiceStepDefinitions.this.methodCalled = true;

                        ModelMetadataResponse response = ModelMetadataResponse.newBuilder().setPlatform("platform")
                                .setName("name")
                                .addInputs(TensorMetadata.newBuilder().setName("tensorName").setDatatype("UINT64")
                                        .addShape(1l).build())
                                .addOutputs(TensorMetadata.newBuilder().setName("tensorName").setDatatype("UINT64")
                                        .addShape(1l).build())
                                .build();

                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void modelInfer(ModelInferRequest request,
                            StreamObserver<ModelInferResponse> responseObserver) {

                        TritonServerServiceStepDefinitions.this.methodCalled = true;
                        TritonServerServiceStepDefinitions.this.inferenceRequest = request;

                        List<InferOutputTensor> outputTensor = new ArrayList<>();
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("FP64").setName("name1")

                                .addShape(1).build());
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("BOOL").setName("name2")
                                .addShape(1).build());
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("BYTES").setName("name3")

                                .addShape(1).build());
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("FP32").setName("name4")

                                .addShape(1).build());
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("INT64").setName("name5")

                                .addShape(1).build());
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("INT32").setName("name6")
                                .addShape(1).build());
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("UINT64").setName("name7")

                                .addShape(1).build());
                        outputTensor.add(InferOutputTensor.newBuilder().setDatatype("UINT32").setName("name8")

                                .addShape(1).build());

                        List<ByteString> rawOutputTensor = new ArrayList<>();
                        rawOutputTensor.add(ByteString.copyFrom(convertDoubleToByteArray(34.76d)));
                        rawOutputTensor.add(ByteString.copyFrom(convertBooleanToByteArray(true)));
                        rawOutputTensor.add(ByteString.copyFrom(new byte[] { 4, 0, 0, 0, 10, 20, -10, -20 }));
                        rawOutputTensor.add(ByteString.copyFrom(convertFloatToByteArray(134.76f)));
                        rawOutputTensor.add(ByteString.copyFrom(convertLongToByteArray(56436l)));
                        rawOutputTensor.add(ByteString.copyFrom(convertIntegerToByteArray(45465)));
                        rawOutputTensor.add(ByteString.copyFrom(convertLongToByteArray(536456l)));
                        rawOutputTensor.add(ByteString.copyFrom(convertIntegerToByteArray(53645)));

                        ModelInferResponse response = ModelInferResponse.newBuilder().addAllOutputs(outputTensor)
                                .addAllRawOutputContents(rawOutputTensor).build();

                        responseObserver.onNext(response);
                        responseObserver.onCompleted();

                    }

                    @Override
                    public void serverLive(ServerLiveRequest request,
                            StreamObserver<ServerLiveResponse> responseObserver) {
                        TritonServerServiceStepDefinitions.this.methodCalled = true;

                        ServerLiveResponse response = ServerLiveResponse.newBuilder().setLive(true).build();
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void modelStatistics(ModelStatisticsRequest request,
                            StreamObserver<ModelStatisticsResponse> responseObserver) {
                        TritonServerServiceStepDefinitions.this.methodCalled = true;

                        if (metricsEnabled) {
                            InferStatistics.Builder firstInferStatisticsBuilder = InferStatistics.newBuilder();
                            firstInferStatisticsBuilder
                                    .setSuccess(StatisticDuration.newBuilder().setCount(42).setNs(30097440).build());
                            firstInferStatisticsBuilder
                                    .setFail(StatisticDuration.newBuilder().setCount(0).setNs(0).build());
                            firstInferStatisticsBuilder
                                    .setQueue(StatisticDuration.newBuilder().setCount(42).setNs(7137056).build());
                            firstInferStatisticsBuilder
                                    .setComputeInput(StatisticDuration.newBuilder().setCount(42).setNs(532768).build());
                            firstInferStatisticsBuilder.setComputeInfer(
                                    StatisticDuration.newBuilder().setCount(42).setNs(4664640).build());
                            firstInferStatisticsBuilder
                                    .setComputeOutput(StatisticDuration.newBuilder().setCount(42).setNs(4736).build());
                            firstInferStatisticsBuilder
                                    .setCacheHit(StatisticDuration.newBuilder().setCount(0).setNs(0).build());
                            firstInferStatisticsBuilder
                                    .setCacheMiss(StatisticDuration.newBuilder().setCount(0).setNs(0).build());

                            InferBatchStatistics.Builder firstInferBatchStatisticsBuilder = InferBatchStatistics
                                    .newBuilder();
                            firstInferBatchStatisticsBuilder.setBatchSize(1);
                            firstInferBatchStatisticsBuilder
                                    .setComputeInput(StatisticDuration.newBuilder().setCount(42).setNs(532768).build());
                            firstInferBatchStatisticsBuilder.setComputeInfer(
                                    StatisticDuration.newBuilder().setCount(42).setNs(4664640).build());
                            firstInferBatchStatisticsBuilder
                                    .setComputeOutput(StatisticDuration.newBuilder().setCount(42).setNs(4736).build());

                            ModelStatistics.Builder firstModelStatisticsBuilder = ModelStatistics.newBuilder();
                            firstModelStatisticsBuilder.setName("identity_long").setVersion("1")
                                    .setLastInference(1739867342484L).setInferenceCount(42).setExecutionCount(42)
                                    .setInferenceStats(firstInferStatisticsBuilder.build())
                                    .addBatchStats(firstInferBatchStatisticsBuilder.build());

                            InferStatistics.Builder secondInferStatisticsBuilder = InferStatistics.newBuilder();
                            secondInferStatisticsBuilder
                                    .setSuccess(StatisticDuration.newBuilder().setCount(42).setNs(278516928).build());
                            secondInferStatisticsBuilder
                                    .setFail(StatisticDuration.newBuilder().setCount(0).setNs(0).build());
                            secondInferStatisticsBuilder
                                    .setQueue(StatisticDuration.newBuilder().setCount(42).setNs(8943104).build());
                            secondInferStatisticsBuilder.setComputeInput(
                                    StatisticDuration.newBuilder().setCount(42).setNs(11304704).build());
                            secondInferStatisticsBuilder.setComputeInfer(
                                    StatisticDuration.newBuilder().setCount(42).setNs(230288448).build());
                            secondInferStatisticsBuilder.setComputeOutput(
                                    StatisticDuration.newBuilder().setCount(42).setNs(26880672).build());
                            secondInferStatisticsBuilder
                                    .setCacheHit(StatisticDuration.newBuilder().setCount(0).setNs(0).build());
                            secondInferStatisticsBuilder
                                    .setCacheMiss(StatisticDuration.newBuilder().setCount(0).setNs(0).build());

                            InferBatchStatistics.Builder secondInferBatchStatisticsBuilder = InferBatchStatistics
                                    .newBuilder();
                            secondInferBatchStatisticsBuilder.setBatchSize(1);
                            secondInferBatchStatisticsBuilder.setComputeInput(
                                    StatisticDuration.newBuilder().setCount(42).setNs(11304704).build());
                            secondInferBatchStatisticsBuilder.setComputeInfer(
                                    StatisticDuration.newBuilder().setCount(42).setNs(230288448).build());
                            secondInferBatchStatisticsBuilder.setComputeOutput(
                                    StatisticDuration.newBuilder().setCount(42).setNs(26880672).build());

                            ModelStatistics.Builder secondModelStatisticsBuilder = ModelStatistics.newBuilder();
                            secondModelStatisticsBuilder.setName("preprocessor").setVersion("1")
                                    .setLastInference(1739867342480L).setInferenceCount(42).setExecutionCount(42)
                                    .setInferenceStats(secondInferStatisticsBuilder.build())
                                    .addBatchStats(secondInferBatchStatisticsBuilder.build());

                            ModelStatisticsResponse response = ModelStatisticsResponse.newBuilder()
                                    .addModelStats(firstModelStatisticsBuilder.build())
                                    .addModelStats(secondModelStatisticsBuilder.build()).build();
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        } else {
                            responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                        }
                    }

                }));
    }

    @BeforeEach
    public void resetStatus() {
        this.exceptionCaught = false;
        this.methodCalled = false;
        this.modelsFound.clear();
        this.modelInfo = Optional.empty();
        this.tensorList.clear();
        this.isEngineReady = false;

        if (this.tritonServerService != null) {
            this.tritonServerService.deactivate();
        }
    }

    private byte[] convertDoubleToByteArray(Double value) {

        ByteBuffer byteBuffer = ByteBuffer.allocate(Double.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putDouble(value);
        return byteBuffer.array();
    }

    private byte[] convertFloatToByteArray(Float value) {

        ByteBuffer byteBuffer = ByteBuffer.allocate(Float.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putFloat(value);
        return byteBuffer.array();
    }

    // private byte[] convertByteToByteArray(Byte value) {
    //
    // ByteBuffer byteBuffer = ByteBuffer.allocate(Byte.BYTES);
    // byteBuffer.put(value);
    // byte[] a = byteBuffer.array();
    // return a;
    // }

    private byte[] convertBooleanToByteArray(Boolean value) {

        ByteBuffer byteBuffer = ByteBuffer.allocate(1);
        byteBuffer.put(value ? (byte) 1 : (byte) 0);
        return byteBuffer.array();
    }

    private byte[] convertLongToByteArray(Long value) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(Long.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putLong(value);
        return byteBuffer.array();
    }

    private byte[] convertIntegerToByteArray(Integer value) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(Integer.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putInt(value);
        return byteBuffer.array();
    }
}
