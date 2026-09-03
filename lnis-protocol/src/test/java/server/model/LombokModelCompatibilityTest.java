package server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import server.model.AgentProtocol.FrameEvidenceMessage;
import server.model.AgentProtocol.Hello;
import server.model.LnisModels.AfsSettings;
import server.model.LnisModels.TransportSettings;
import org.junit.jupiter.api.Test;

/** record를 Lombok 불변 클래스로 바꾼 뒤에도 기존 생성·JSON·방어적 복사 계약을 검증한다. */
class LombokModelCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void jsonFieldNamesAndValuesRoundTrip() throws Exception {
        Hello source = new Hello("1.0.0", 1, "Windows", "amd64", java.util.Map.of("afs", true), List.of("192.0.2.1"));

        String encoded = json.writeValueAsString(source);
        Hello decoded = json.readValue(encoded, Hello.class);

        assertEquals(source, decoded);
        assertEquals("1.0.0", json.readTree(encoded).get("agentVersion").asText());
        assertEquals("192.0.2.1", json.readTree(encoded).get("ipv4Addresses").get(0).asText());
    }

    @Test
    void constructorsKeepExistingDefaultsAndValidation() throws Exception {
        TransportSettings transport = json.readValue("{}", TransportSettings.class);

        assertEquals("255.255.255.255", transport.broadcastAddress());
        assertEquals(45821, transport.dataPort());
        assertEquals(45822, transport.resultPort());
        assertEquals(3, transport.repeatCount());
        assertEquals(1, new AfsSettings(null).prn());
        assertThrows(IllegalArgumentException.class, () -> new AfsSettings(9));
    }

    @Test
    void constructorStillDefensivelyCopiesMutableInputs() {
        byte[] frame = {1, 2, 3};
        List<Integer> positions = new ArrayList<>(List.of(7));
        FrameEvidenceMessage message = new FrameEvidenceMessage(
                0, frame, null, null, null, positions,
                false, false, false, false, false,
                0, 0, 0, false, null, null, null);

        frame[0] = 9;
        positions.add(8);

        assertEquals(1, message.referenceFrame()[0]);
        assertNotSame(frame, message.referenceFrame());
        assertEquals(List.of(7), message.injectedBitPositions());
        assertThrows(UnsupportedOperationException.class, () -> message.injectedBitPositions().add(8));
    }
}
