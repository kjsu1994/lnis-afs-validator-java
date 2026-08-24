package kr.co.lnis.agent.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds the LNIS server on the local LAN after a configured connection fails. */
final class ServerDiscovery {
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(700);
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(4);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(400))
            .build();

    Optional<URI> discover(int port) {
        return discover(port, candidateHosts(), DISCOVERY_TIMEOUT);
    }

    Optional<URI> discover(int port, Collection<String> candidates, Duration overallTimeout) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = new ExecutorCompletionService<URI>(executor);
            for (String host : candidates) {
                completion.submit(() -> probe(host, port));
            }
            long deadline = System.nanoTime() + overallTimeout.toNanos();
            for (int remaining = candidates.size(); remaining > 0; remaining--) {
                long wait = deadline - System.nanoTime();
                if (wait <= 0) {
                    return Optional.empty();
                }
                var completed = completion.poll(wait, TimeUnit.NANOSECONDS);
                if (completed == null) {
                    return Optional.empty();
                }
                URI result = completed.get();
                if (result != null) {
                    return Optional.of(result);
                }
            }
        } catch (Exception ignored) {
            // The next reconnect cycle retries discovery.
        }
        return Optional.empty();
    }

    private URI probe(String host, int port) {
        try {
            URI endpoint = URI.create(
                    "http://" + host + ":" + port + "/lnis/api/v1/discovery");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            DiscoveryResponse value = json.readValue(response.body(), DiscoveryResponse.class);
            if (!"lnis-server".equals(value.service())
                    || value.agentWebSocketPath() == null
                    || !value.agentWebSocketPath().startsWith("/")) {
                return null;
            }
            return URI.create(
                    "ws://" + host + ":" + port + value.agentWebSocketPath());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Collection<String> candidateHosts() {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add("127.0.0.1");
        try {
            NetworkInterface.networkInterfaces()
                    .filter(network -> {
                        try {
                            return network.isUp()
                                    && !network.isLoopback()
                                    && !network.isVirtual();
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(Inet4Address.class::isInstance)
                    .map(Inet4Address.class::cast)
                    .filter(Inet4Address::isSiteLocalAddress)
                    .forEach(address -> addSlash24(hosts, address.getAddress()));
        } catch (Exception ignored) {
            // Keep localhost as a candidate.
        }
        return hosts;
    }

    private static void addSlash24(Collection<String> hosts, byte[] address) {
        int first = Byte.toUnsignedInt(address[0]);
        int second = Byte.toUnsignedInt(address[1]);
        int third = Byte.toUnsignedInt(address[2]);
        for (int last = 1; last < 255; last++) {
            hosts.add(first + "." + second + "." + third + "." + last);
        }
    }

    private record DiscoveryResponse(String service, String agentWebSocketPath) {}
}
