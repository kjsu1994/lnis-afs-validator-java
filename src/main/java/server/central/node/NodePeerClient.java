package server.central.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 상대 노드 조회 전용 클라이언트다. 자동 재전송과 응답에 따른 대상 주소 변경은 하지 않는다. */
@Service
@Profile("node")
public class NodePeerClient {
    private static final int MAX_STATUS_BYTES = 16 * 1024;
    private final NodeProperties nodeProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public NodePeerClient(NodeProperties nodeProperties, ObjectMapper objectMapper)
    {
        this.nodeProperties = nodeProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public NodeDto.StatusResponse status()
    {
        if (!nodeProperties.peerConfigured()) {
            throw new IllegalStateException("상대 노드 주소와 관리 토큰을 설정하세요.");
        }
        HttpRequest request = HttpRequest.newBuilder(nodeProperties.getPeerBaseUrl()
                        .resolve("/lnis/api/v1/node/peer/status"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + nodeProperties.getManagementToken())
                .GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = httpClient.sendAsync(request,
                information -> new LimitedBodySubscriber(MAX_STATUS_BYTES));
        try {
            // 헤더 이후 본문이 멈추는 경우도 포함해 전체 조회 시간에 제한을 둔다.
            HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("상대 노드 상태 조회 실패: HTTP " + response.statusCode());
            }
            byte[] bytes = response.body();
            NodeDto.StatusResponse status = objectMapper.readValue(bytes, NodeDto.StatusResponse.class);
            if (status.getProtocolVersion() != NodeStatusService.PROTOCOL_VERSION
                    || !nodeProperties.getPeerAgentId().equals(status.getAgentId())
                    || status.getRole() == null || status.getRole() == nodeProperties.getRole()) {
                throw new IllegalStateException("상대 노드의 ID, 역할 또는 관리 프로토콜이 일치하지 않습니다.");
            }
            return status;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("상대 노드 조회가 중단되었습니다.", error);
        } catch (TimeoutException error) {
            throw new IllegalStateException("상대 노드 응답 제한 시간 초과", error);
        } catch (ExecutionException | java.io.IOException error) {
            throw new IllegalStateException("상대 노드에 연결할 수 없습니다.", error);
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }
}
