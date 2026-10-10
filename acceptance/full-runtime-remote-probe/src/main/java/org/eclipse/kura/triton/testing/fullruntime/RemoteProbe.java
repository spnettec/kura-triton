/* SPDX-License-Identifier: EPL-2.0 */
package org.eclipse.kura.triton.testing.fullruntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.google.gson.Gson;
import org.eclipse.kura.ai.inference.InferenceEngineService;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.system.SystemService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;

/** Observes the unmodified remote implementation through production SCR and configuration services. */
public final class RemoteProbe implements BundleActivator {
    private static final String BUNDLE = "org.eclipse.kura.ai.triton.server";
    private static final String FACTORY = BUNDLE + ".TritonServerRemoteService";
    private final List<ServiceReference<?>> references = new ArrayList<>();
    private Thread worker;

    @Override
    public void start(BundleContext context) {
        worker = new Thread(() -> run(context), "MacTritonRemoteAcceptance");
        worker.start();
    }

    @Override
    public void stop(BundleContext context) throws InterruptedException {
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
            worker.join(5_000);
        }
    }

    private void run(BundleContext context) {
        Path root = Path.of(System.getProperty("kura.acceptance.root")).toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("kura.home")).toAbsolutePath().normalize();
        String pid = "acceptance.mac.triton.remote." + UUID.randomUUID();
        Map<String, Object> result = new LinkedHashMap<>();
        ConfigurationService configuration = null;
        InferenceEngineService engine = null;
        Object lastChannel = null;
        boolean created = false;
        Throwable failure = null;
        try {
            require(home.startsWith(root) && Files.isRegularFile(home.resolve(".triton-remote-acceptance-owned")),
                    "Owned isolated profile");
            SystemService system = service(context, SystemService.class, null);
            require(system.getClass().getName().equals("org.eclipse.kura.core.system.SystemServiceImpl"), "Production host service");
            require(Path.of(system.getKuraHome()).toAbsolutePath().normalize().equals(home), "Relocated host profile");
            configuration = service(context, ConfigurationService.class, null);
            ServiceComponentRuntime scr = service(context, ServiceComponentRuntime.class, null);
            Bundle triton = java.util.Arrays.stream(context.getBundles())
                    .filter(b -> BUNDLE.equals(b.getSymbolicName())).findFirst().orElseThrow();
            require(triton.getState() == Bundle.ACTIVE, "Production Triton bundle active");
            result.put("bundleLocation", triton.getLocation());
            result.put("bundleVersion", triton.getVersion().toString());
            result.put("bundleCount", context.getBundles().length);
            result.put("componentDescriptions", scr.getComponentDescriptionDTOs(triton).stream().map(d -> d.name).sorted().toList());
            ConfigurationService current = configuration;
            await(() -> current.getFactoryComponentPids().contains(FACTORY), "Remote metatype factory discovery");
            result.put("nativeInvalidConfiguration", invalidLocalFactory(context, configuration, scr, triton, "Native", pid + ".native"));
            int orchestrationServices = context.getServiceReferences("org.eclipse.kura.container.orchestration.ContainerOrchestrationService", null).length;
            result.put("containerOrchestrationProviderCount", orchestrationServices);
            if (orchestrationServices > 0) {
                result.put("containerInvalidConfiguration", invalidLocalFactory(context, configuration, scr, triton, "Container", pid + ".container"));
            } else {
                result.put("containerInvalidConfiguration", Map.of("verified", false,
                        "remaining", "Actual ContainerOrchestrationService is absent in this Mac profile"));
            }
            Map<String, Object> properties = new HashMap<>();
            properties.put("server.address", "127.0.0.1");
            properties.put("server.ports", new Integer[] { unusedPort(), unusedPort(), unusedPort() });
            properties.put("models", "");
            properties.put("timeout", 1);
            created = true;
            configuration.createFactoryConfiguration(FACTORY, pid, properties, true);
            engine = service(context, InferenceEngineService.class, "(kura.service.pid=" + pid + ")");
            require(engine.getClass().getName().equals(BUNDLE + ".TritonServerServiceRemoteImpl"), "Actual remote implementation");
            require(FrameworkUtil.getBundle(engine.getClass()) == triton, "Implementation from production bundle");
            InferenceEngineService actual = engine;
            await(() -> active(scr, triton, pid), "Real SCR ACTIVE configuration");
            result.put("createdPid", pid);
            result.put("activeConfiguration", true);
            result.put("privilegedExecutor", field(engine, "commandExecutorService").getClass().getName());
            result.put("cryptoService", field(engine, "cryptoService").getClass().getName());
            require(result.get("privilegedExecutor").equals("org.eclipse.kura.core.linux.executor.privileged.PrivilegedExecutorServiceImpl"),
                    "Production executor binding; remote mode issues no command");
            require(FrameworkUtil.getBundle(field(engine, "cryptoService").getClass()).getSymbolicName()
                    .equals("org.eclipse.kura.core.crypto"), "Production crypto binding");
            Object first = field(engine, "grpcChannel");
            require(first != null && !channelState(first, "isShutdown"), "Actual gRPC channel construction");
            result.put("channelImplementation", first.getClass().getName());
            // No server is supplied. This is a transport/linkage negative check, not Triton inference acceptance.
            require(!engine.isEngineReady(), "Absent loopback server must not be reported ready");
            result.put("absentServerReturnsNotReady", true);
            properties.put("server.ports", new Integer[] { unusedPort(), unusedPort(), unusedPort() });
            configuration.updateConfiguration(pid, new HashMap<>(properties), false);
            await(() -> applied(actual, properties) && liveReplacement(actual, first), "Changed configuration replaces channel");
            require(channelState(first, "isShutdown") && channelState(first, "isTerminated"), "Old channel closes and terminates");
            Object second = field(engine, "grpcChannel");
            require(second != null && !channelState(second, "isShutdown"), "Replacement channel live");
            result.put("changedUpdateClosesReplacedChannel", true);
            properties.put("server.address", "");
            configuration.updateConfiguration(pid, new HashMap<>(properties), false);
            await(() -> applied(actual, properties) && channelState(second, "isTerminated"), "Invalid configuration closes live channel");
            require(active(scr, triton, pid), "Invalid remote address keeps the configurable service active");
            result.put("invalidUpdateClosesChannel", true);
            properties.put("server.address", "127.0.0.1");
            configuration.updateConfiguration(pid, new HashMap<>(properties), false);
            await(() -> applied(actual, properties) && liveReplacement(actual, second), "Valid configuration restores a channel");
            lastChannel = field(engine, "grpcChannel");
            require(!channelState(lastChannel, "isShutdown"), "Recovered channel live");
            require(configuration.getComponentConfiguration(pid).getConfigurationProperties().get("server.address").equals("127.0.0.1"),
                    "Configuration round trip");
            result.put("validUpdateRecoversChannel", true);
            require(scr.getComponentDescriptionDTOs(triton).stream().filter(d -> !FACTORY.equals(d.name))
                    .allMatch(d -> scr.getComponentConfigurationDTOs(d).isEmpty()), "No native/container configuration or process started");
            result.put("nativeAndContainerConfigurationsCleaned", true);
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                if (created && configuration != null) {
                    configuration.deleteFactoryConfiguration(pid, true);
                    await(() -> absent(context, pid),
                            "Owned remote service deletion");
                    if (lastChannel != null) {
                        Object finalChannel = lastChannel;
                        await(() -> channelState(finalChannel, "isTerminated"), "Deleted service closes its final channel");
                    }
                    require(!configuration.getConfigurableComponentPids().contains(pid), "Owned configuration removed");
                    result.put("serviceConfigurationAndChannelCleanup", true);
                }
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            references.forEach(context::ungetService);
            result.put("passed", failure == null);
            if (failure != null) {
                failure.printStackTrace();
                result.put("error", failure.toString());
            }
            try {
                Files.writeString(root.resolve("triton-remote-result.json"), new Gson().toJson(result) + "\n");
            } catch (Exception output) {
                output.printStackTrace();
            }
        }
    }

    private static boolean active(ServiceComponentRuntime scr, Bundle bundle, String pid) {
        return active(scr, bundle, FACTORY, pid);
    }

    private static boolean active(ServiceComponentRuntime scr, Bundle bundle, String factory, String pid) {
        var description = scr.getComponentDescriptionDTO(bundle, factory);
        return description != null && scr.getComponentConfigurationDTOs(description).stream()
                .anyMatch(c -> c.state == ComponentConfigurationDTO.ACTIVE && pid.equals(c.properties.get("kura.service.pid")));
    }

    private Map<String, Object> invalidLocalFactory(BundleContext context, ConfigurationService configuration,
            ServiceComponentRuntime scr, Bundle triton, String kind, String pid) throws Exception {
        String factory = BUNDLE + ".TritonServer" + kind + "Service";
        await(() -> configuration.getFactoryComponentPids().contains(factory), kind + " metatype factory discovery");
        Map<String, Object> properties = new HashMap<>();
        properties.put("local.backends.path", "");
        properties.put("local.model.repository.path", "");
        properties.put("container.image", "");
        properties.put("container.image.tag", "");
        properties.put("models", "");
        try {
            configuration.createFactoryConfiguration(factory, pid, properties, true);
            InferenceEngineService value = service(context, InferenceEngineService.class, "(kura.service.pid=" + pid + ")");
            await(() -> active(scr, triton, factory, pid), "Actual invalid " + kind + " configuration ACTIVE");
            require(value.getClass().getName().equals(BUNDLE + ".TritonServerService" + kind + "Impl"), "Actual " + kind + " implementation");
            require(FrameworkUtil.getBundle(value.getClass()) == triton, "Production " + kind + " bundle");
            require(field(value, "grpcChannel") == null && field(value, "tritonServerInstanceManager") == null,
                    "Invalid " + kind + " configuration must not start gRPC/native/container resources");
            return Map.of("verified", true, "pid", pid, "state", ComponentConfigurationDTO.ACTIVE,
                    "implementation", value.getClass().getName(), "managerAndChannelAbsent", true);
        } finally {
            configuration.deleteFactoryConfiguration(pid, true);
            await(() -> absent(context, pid), "Owned " + kind + " configuration cleanup");
            require(!configuration.getConfigurableComponentPids().contains(pid), "Owned " + kind + " PID removed");
        }
    }

    private static boolean absent(BundleContext context, String pid) {
        try {
            return context.getServiceReferences(InferenceEngineService.class, "(kura.service.pid=" + pid + ")").isEmpty();
        } catch (org.osgi.framework.InvalidSyntaxException error) { throw new AssertionError(error); }
    }

    private static boolean applied(Object engine, Map<String, Object> properties) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) field(field(engine, "options"), "properties");
        return java.util.Objects.equals(values.get("server.address"), properties.get("server.address"))
                && java.util.Objects.deepEquals(values.get("server.ports"), properties.get("server.ports"));
    }

    private static boolean liveReplacement(Object engine, Object previous) {
        Object channel = field(engine, "grpcChannel");
        return channel != null && channel != previous && !channelState(channel, "isShutdown");
    }

    private <T> T service(BundleContext context, Class<T> type, String filter) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        do {
            var candidates = context.getServiceReferences(type, filter);
            for (var ref : candidates) {
                T value = context.getService(ref);
                if (value != null) { references.add(ref); return value; }
            }
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        throw new AssertionError("Service did not arrive: " + type.getName() + " " + filter);
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field value = type.getDeclaredField(name); value.setAccessible(true); return value.get(target);
            } catch (NoSuchFieldException missing) {
                // The implementation inherits its gRPC state and bindings.
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        throw new AssertionError("Missing production field: " + name);
    }

    private static boolean channelState(Object channel, String method) {
        try {
            Method state = channel.getClass().getMethod(method); state.setAccessible(true); return (Boolean) state.invoke(channel);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { if (condition.getAsBoolean()) return; Thread.sleep(100); } while (System.nanoTime() < end);
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
