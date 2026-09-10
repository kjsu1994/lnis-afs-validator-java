package server.central.dtn;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;


/**
 * 송신 PC와 수신 PC의 DTN/HDTN Adapter에
 * 시험 동작 모드를 전달한다.
 *
 * Adapter 내부에서 실제 DTN/HDTN 프로세스 제어를 담당한다.
 */
@Service
@RequiredArgsConstructor
public class DtnAdapterControlService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient =
            HttpClient
                    .newBuilder()
                    .connectTimeout(
                            Duration.ofSeconds(3)
                    )
                    .build();

    /*
     * 전체 제어 Endpoint를 환경변수로 받는다.
     *
     * 특정 /mode 등의 path를 LNIS가 임의로 가정하지 않는다.
     */
    @Value("${lnis.dtn.sender-adapter-control-url:}")
    private String senderAdapterControlUrl;


    @Value("${lnis.dtn.receiver-adapter-control-url:}")
    private String receiverAdapterControlUrl;


    /**
     * 송신측 Adapter와 수신측 Adapter에 각각
     * DTN/HDTN 모드를 설정한다.
     */
    public void changeMode(
            String senderMode,
            String receiverMode)
    {

        if (
                senderAdapterControlUrl == null
                        || senderAdapterControlUrl.isBlank()
        ) {

            throw new IllegalStateException(
                    "송신 Adapter 제어 URL이 설정되지 않았습니다."
            );
        }


        if (
                receiverAdapterControlUrl == null
                        || receiverAdapterControlUrl.isBlank()
        ) {

            throw new IllegalStateException(
                    "수신 Adapter 제어 URL이 설정되지 않았습니다."
            );
        }


        /*
         * 송신 Adapter
         */
        sendMode(
                senderAdapterControlUrl,
                senderMode,
                "송신"
        );


        /*
         * 수신 Adapter
         */
        sendMode(
                receiverAdapterControlUrl,
                receiverMode,
                "수신"
        );
    }


    private void sendMode(
            String url,
            String mode,
            String adapterName)
    {

        try {

            String json =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "mode",
                                    mode
                            )
                    );


            HttpRequest request =
                    HttpRequest
                            .newBuilder()
                            .uri(
                                    URI.create(url)
                            )
                            .timeout(
                                    Duration.ofSeconds(5)
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(json)
                            )
                            .build();


            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers
                                    .ofString()
                    );


            if (
                    response.statusCode() < 200
                            || response.statusCode() >= 300
            ) {

                throw new IllegalStateException(
                        adapterName
                                + " Adapter 설정 실패. HTTP "
                                + response.statusCode()
                                + " / "
                                + response.body()
                );
            }

        }
        catch (InterruptedException error) {

            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    adapterName
                            + " Adapter 호출이 중단되었습니다.",
                    error
            );

        }
        catch (Exception error) {

            throw new IllegalStateException(
                    adapterName
                            + " Adapter 호출 실패: "
                            + error.getMessage(),
                    error
            );

        }
    }
}