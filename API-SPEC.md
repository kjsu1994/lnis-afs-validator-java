# LNIS API 명세서

현재 소스 코드에 구현된 LNIS 중앙 서버의 REST API와 WebSocket 계약입니다.

- 기준 버전: `1.0.0`
- Agent WebSocket protocol: `2`
- 기본 서버 주소: `http://192.168.1.72:8088`
- REST 기본 경로: `/lnis/api/v1`
- 인코딩: UTF-8
- 시간: ISO-8601 UTC 문자열
- 식별자: UUID

## 1. 공통 규칙

### Content-Type

| 용도 | Content-Type |
|---|---|
| 일반 REST | `application/json` |
| GRAW 청크 | `application/octet-stream` |
| CSV | `text/csv;charset=UTF-8` |
| Excel | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |

JSON 응답에서는 값이 `null`인 속성이 생략될 수 있습니다.

### 인증

현재 브라우저 REST API에는 사용자 인증이 없습니다. 신뢰할 수 있는 시험 LAN에서만 사용해야 합니다.

Agent WebSocket은 다음 두 헤더로 인증합니다.

```http
X-LNIS-Agent-Id: sender-1
Authorization: Bearer <agent-token>
```

Agent ID와 token은 서버의 `LNIS_AGENT_TOKENS` 설정과 일치해야 합니다.

### HTTP 상태 코드

| 상태 | 의미 |
|---|---|
| `200 OK` | 성공 |
| `204 No Content` | 활성 세션 없음 |
| `400 Bad Request` | 잘못된 값·역할·파일명 또는 존재하지 않는 리소스 |
| `409 Conflict` | Agent 오프라인, 미완료 입력, 중복 시험 또는 상태 충돌 |
| `404 Not Found` | 등록되지 않은 URL |

업무 예외는 RFC 9457 Problem Detail로 반환됩니다.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Another test session is active",
  "instance": "/lnis/api/v1/sessions",
  "code": "CONFLICT"
}
```

`IllegalArgumentException`과 요청 검증 오류는 `400`, `IllegalStateException`은 `409`입니다.

## 2. API 목록

아래 경로에는 모두 `/lnis/api/v1`을 앞에 붙입니다.

| 구분 | Method | 경로 | 설명 |
|---|---|---|---|
| Discovery | GET | `/discovery` | 중앙 서버 식별 |
| Agent | GET | `/agents` | 전체 Agent 조회 |
| Agent | GET | `/agents/{agentId}` | Agent 조회 |
| Agent | POST | `/agents/{agentId}/serial-ports/refresh` | COM 포트 목록 요청 |
| Input | POST | `/inputs` | GRAW 입력 생성 |
| Input | PUT | `/inputs/{inputId}/chunks/{index}` | GRAW 청크 업로드 |
| Input | POST | `/inputs/{inputId}/complete` | 입력 검증·완료 |
| Input | GET | `/inputs/{inputId}` | 입력 조회 |
| Input | DELETE | `/inputs/{inputId}` | 입력 삭제 |
| Capture | POST | `/captures` | GNSS 수집 시작 |
| Capture | POST | `/captures/{captureId}/stop` | GNSS 수집 중지 |
| Capture | POST | `/captures/{captureId}/complete` | 수집 입력 완료 |
| Session | POST | `/sessions` | AFS 시험 시작 |
| Session | GET | `/sessions/active` | 활성 시험 조회 |
| Session | GET | `/sessions/{sessionId}` | 시험 조회 |
| Session | POST | `/sessions/{sessionId}/cancel` | 시험 취소 |
| Evidence | GET | `/sessions/{sessionId}/frame-evidence` | 프레임 증거 목록 |
| Evidence | GET | `/sessions/{sessionId}/frame-evidence/{frameIndex}` | 프레임 증거 상세 |
| Evidence | GET | `/sessions/{sessionId}/frame-evidence/artifacts/{fileName}` | 프레임 증거 파일 |
| Artifact | GET | `/sessions/{sessionId}/artifacts/{fileName}` | 통합 결과 파일 |
| Artifact | GET | `/sessions/{sessionId}/artifacts/{role}/{fileName}` | 역할별 결과 파일 |
| Actuator | GET | `/actuator/health` | 서버 상태 |
| Actuator | GET | `/actuator/health/liveness` | 생존 상태 |
| Actuator | GET | `/actuator/health/readiness` | 준비 상태 |
| Actuator | GET | `/actuator/info` | 서버 정보 |

## 3. Discovery

```http
GET /lnis/api/v1/discovery
```

```json
{
  "service": "lnis-server",
  "agentWebSocketPath": "/lnis/agent/ws"
}
```

Windows Agent가 LAN에서 중앙 서버 후보를 식별할 때 사용합니다.

## 4. Agent

### 전체 Agent 조회

```http
GET /lnis/api/v1/agents
```

```json
[
  {
    "agentId": "sender-1",
    "role": "SENDER",
    "state": "READY",
    "lastSeen": "2026-09-04T02:17:57.277580Z",
    "version": "1.0.0",
    "codecAbiVersion": 1,
    "os": "Windows 11",
    "architecture": "amd64",
    "ipv4Addresses": ["192.168.1.72"],
    "error": null
  }
]
```

`role`은 `SENDER`, `RECEIVER`이고 `state`는 `OFFLINE`, `CONNECTING`, `READY`, `BUSY`, `ERROR` 중 하나입니다.

### Agent 한 개 조회

```http
GET /lnis/api/v1/agents/{agentId}
```

응답은 전체 조회의 단일 항목과 같습니다.

### COM 포트 새로고침

```http
POST /lnis/api/v1/agents/{agentId}/serial-ports/refresh
```

```json
{
  "commandId": "7f7fd0f1-998b-4b31-b282-a667062340af",
  "accepted": true
}
```

이는 명령 전송 접수만 의미합니다. 실제 포트 목록은 브라우저 WebSocket으로 비동기 전달됩니다.

## 5. GRAW Input

### 입력 생성

```http
POST /lnis/api/v1/inputs
Content-Type: application/json
```

```json
{
  "fileName": "capture.graw",
  "size": 464,
  "kind": "GRAW_UPLOAD"
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `fileName` | O | 경로가 아닌 표시 파일명 |
| `size` | O | 예상 byte 크기, 0 이상 |
| `kind` | X | `GRAW_UPLOAD`, `GNSS_CAPTURE`; 기본 `GRAW_UPLOAD` |

### 청크 업로드

```http
PUT /lnis/api/v1/inputs/{inputId}/chunks/{index}
Content-Type: application/octet-stream
```

본문은 Base64나 JSON이 아닌 GRAW 원본 byte입니다.

- 청크 크기: 1~1,048,576 byte
- 첫 index: `0`
- 다음 index는 현재 `chunkCount`와 정확히 같아야 함
- 완료된 입력에는 추가 불가

```powershell
$bytes = [IO.File]::ReadAllBytes('C:\sample\capture.graw')
Invoke-RestMethod `
  -Uri 'http://192.168.1.72:8088/lnis/api/v1/inputs/{inputId}/chunks/0' `
  -Method Put -ContentType 'application/octet-stream' -Body $bytes
```

### 입력 완료

```http
POST /lnis/api/v1/inputs/{inputId}/complete
```

모든 청크, 선언 크기, length-prefixed GRAW record 구조, CRC, 잘림 여부와 전체 SHA-256을 검증합니다.

### 입력 조회

```http
GET /lnis/api/v1/inputs/{inputId}
```

| 필드 | 설명 |
|---|---|
| `inputId` | 입력 UUID |
| `kind` | 입력 종류 |
| `fileName` | 표시 파일명 |
| `declaredSize`, `receivedSize` | 선언·수신 byte |
| `chunkCount`, `recordCount` | 청크·GRAW record 수 |
| `sha256` | 완료 입력 SHA-256 |
| `complete` | 완료 여부 |
| `createdAt`, `completedAt` | 생성·완료 시각 |

### 입력 삭제

```http
DELETE /lnis/api/v1/inputs/{inputId}
```

```json
{"removed": true}
```

명시적 삭제는 세션 참조 여부와 관계없이 메타데이터와 파일을 제거하므로 주의합니다.

## 6. GNSS Capture

### 수집 시작

```http
POST /lnis/api/v1/captures
Content-Type: application/json
```

```json
{
  "senderAgentId": "sender-1",
  "portName": "COM3",
  "baudRate": 115200,
  "protocolId": "ubx",
  "sessionName": "현장 수집 1",
  "receiverModel": "ZED-F9P",
  "firmwareVersion": "1.32",
  "dtrEnabled": false,
  "rtsEnabled": false
}
```

- `senderAgentId`, `portName`, `protocolId`: 필수
- `baudRate`: 1,200~4,000,000
- protocol 대표 값: `ubx`, `lnis-canonical-v1`, `raw-only`

성공 시 `kind=GNSS_CAPTURE`, `complete=false`인 Input 객체를 반환합니다. Agent 명령 전송 실패 시 생성한 입력을 보상 삭제합니다.

### 수집 중지

```http
POST /lnis/api/v1/captures/{captureId}/stop?senderAgentId=sender-1
```

```json
{"commandId": "98bab072-3ea1-46d3-bf07-42a71bfd0f12", "accepted": true}
```

### 수집 입력 완료

```http
POST /lnis/api/v1/captures/{captureId}/complete
```

canonical GRAW 검증 후 완료된 Input 객체를 반환합니다. `raw-only` 입력은 시험 입력으로 완료할 수 없습니다.

## 7. Session

### 시험 생성

```http
POST /lnis/api/v1/sessions
Content-Type: application/json
```

```json
{
  "senderAgentId": "sender-1",
  "receiverAgentId": "receiver-1",
  "inputId": "4c0694f1-ce13-4a03-90d1-94288775f7bd",
  "afs": {"prn": 1},
  "transport": {
    "broadcastAddress": "192.168.1.255",
    "dataPort": 45821,
    "resultPort": 45822,
    "repeatCount": 3,
    "resultTimeoutSeconds": 30,
    "endGraceMilliseconds": 1000,
    "probeIntervalMilliseconds": 1000
  },
  "options": {
    "testType": "TEST_A_NORMAL",
    "errorCount": 1,
    "errorSeed": 1,
    "syncDamageInterval": 10,
    "dropRatePercent": 0,
    "dropSeed": 1,
    "thresholds": {}
  }
}
```

시험 종류:

- `TEST_A_NORMAL`: 정상 송수신
- `TEST_B_RANDOM_ERRORS`: 임의 비트 오류
- `TEST_C_BURST_ERRORS`: 연속 비트 오류
- `TEST_D_SYNC_RECOVERY`: 동기 손상 후 재동기
- `TEST_E_UDP_DROP`: UDP 복제본 Drop

검증 조건:

- 입력은 `complete=true`
- Agent가 존재하고 Sender/Receiver 역할이 일치
- 동시에 하나의 활성 시험만 허용
- 두 포트는 1~65,535이며 서로 달라야 함
- `repeatCount`: 1~20
- `afs.prn`: 1~8
- Test B/C `errorCount`: 1~5,880
- Test D `errorCount`: 1~68, `syncDamageInterval`: 1 이상
- Test E `dropRatePercent`: 0~100

| 0 또는 생략 시 기본값 | 값 |
|---|---|
| `broadcastAddress` | `255.255.255.255` |
| `dataPort`, `resultPort` | `45821`, `45822` |
| `repeatCount` | `3` |
| `resultTimeoutSeconds` | `30` |
| `endGraceMilliseconds`, `probeIntervalMilliseconds` | `1000` |
| `afs.prn` | `1` |
| `testType` | `TEST_A_NORMAL` |
| `errorCount`, `errorSeed`, `dropSeed` | `1` |
| `syncDamageInterval` | `10` |

처리 순서:

```text
검증 → H2 lock → WAITING_RECEIVER 저장 → Receiver ARM
     → Sender GRAW 전달 → Sender START
```

중간 실패 시 양쪽 Agent에 `CANCEL_SESSION`을 시도하고 세션을 `FAILED`로 저장한 후 lock을 해제합니다.

### 활성 시험

```http
GET /lnis/api/v1/sessions/active
```

활성 시험이 있으면 `200`과 SessionSnapshot, 없으면 `204`입니다.

### 시험 조회

```http
GET /lnis/api/v1/sessions/{sessionId}
```

| 필드 | 설명 |
|---|---|
| `sessionId` | 시험 UUID |
| `state` | 실행 상태 |
| `testType` | Test 종류 |
| `senderAgentId`, `receiverAgentId` | 참여 Agent |
| `inputId` | 입력 UUID |
| `progress` | 0~100 |
| `message` | 단계 또는 종료 사유 |
| `verdict` | `PASS`, `FAIL`, `INCONCLUSIVE` |
| `createdAt`, `updatedAt` | 생성·갱신 시각 |
| `txResult`, `rxResult` | 역할별 결과, 미도착 시 생략 |

상태 값:

`CREATED`, `WAITING_RECEIVER`, `TRANSMITTING`, `EVALUATING`, `COMPLETED`, `CANCELLED`, `FAILED`, `INCONCLUSIVE`

### 시험 취소

```http
POST /lnis/api/v1/sessions/{sessionId}/cancel
```

양쪽 Agent에 취소를 각각 시도하고 중앙 상태를 `CANCELLED`로 저장합니다. 한 Agent가 오프라인이어도 나머지 취소와 lock 해제를 계속합니다.

## 8. 결과 산출물

### 통합 결과

```http
GET /lnis/api/v1/sessions/{sessionId}/artifacts/lnis-report.json
GET /lnis/api/v1/sessions/{sessionId}/artifacts/lnis-report.xlsx
```

통합 JSON 최상위 필드:

```json
{
  "schemaVersion": 1,
  "sessionId": "...",
  "generatedAt": "...",
  "senderResult": {},
  "receiverResult": {},
  "frameEvidence": []
}
```

한 역할 결과만 있어도 생성되지만 양쪽 결과가 모두 없으면 `400`입니다.

### 역할별 결과

```http
GET /lnis/api/v1/sessions/{sessionId}/artifacts/{role}/{fileName}
```

- Sender role: `tx`, `sender`
- Receiver role: `rx`, `receiver`
- 파일: `result.json`, `metrics-summary.csv`, `metrics-timeseries.csv`

RoleResult에는 `schemaVersion`, `sessionId`, `role`, `verdict`, `completedAt`, `integrity`, `metrics`, `counters`, `samples`, `error`가 포함됩니다.

## 9. Frame Evidence

### 목록

```http
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence
```

프레임 번호는 `0`부터 시작합니다. 주요 필드는 다음과 같습니다.

- 증거: `senderEvidenceAvailable`, `receiverEvidenceAvailable`
- Decoder/CRC: `decoderCompleted`, `decodeSucceeded`, `sb2CrcValid`, `sb3CrcValid`, `sb4CrcValid`
- 판정 변경량: `sb2DecisionChanges`, `sb3DecisionChanges`, `sb4DecisionChanges`
- 해시: `referenceSha256`, `transmittedSha256`, `receivedSha256`, `reencodedSha256`
- 차이 수: `referenceToTransmittedDifferences`, `transmittedToReceivedDifferences`, `referenceToReencodedDifferences`
- 진단: `injectedBitPositions`, `intentionalSyncRejection`, `failureReason`, `interpretation`, `sb2Ephemeris`

### 상세

```http
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/{frameIndex}
```

```json
{
  "summary": {},
  "referenceFrame": "<Base64>",
  "transmittedFrame": "<Base64>",
  "receivedFrame": "<Base64>",
  "reencodedFrame": "<Base64>",
  "referenceToTransmittedPositions": [123],
  "transmittedToReceivedPositions": [],
  "referenceToReencodedPositions": []
}
```

각 프레임 원문은 750 byte이며 JSON에서는 Base64입니다.

### 다운로드

```http
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/artifacts/frame-evidence.json
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/artifacts/frame-diff-summary.csv
```

## 10. WebSocket

### 브라우저 상태

```text
ws://192.168.1.72:8088/lnis/ws/status
```

브라우저는 서버가 방송하는 이벤트를 구독하며 별도 인증은 없습니다.

```json
{
  "sequence": 101,
  "type": "SESSION_STATUS",
  "occurredAt": "2026-09-04T02:17:57.277580Z",
  "agentId": "sender-1",
  "role": "SENDER",
  "sessionId": "bf17461d-4d05-4e71-8944-8f409d96031c",
  "payload": {}
}
```

이벤트 종류:

`AGENT_STATUS`, `GNSS_STATUS`, `TX_STATUS`, `RX_STATUS`, `SESSION_STATUS`, `RESULT`, `ERROR`

WebSocket은 실시간 표시용입니다. 재접속 기준 상태는 `/agents`, `/sessions/active`, `/sessions/{sessionId}`로 복원합니다.

### Agent 제어

```text
ws://192.168.1.72:8088/lnis/agent/ws
```

Handshake에는 `X-LNIS-Agent-Id`와 `Authorization: Bearer ...`가 필요합니다.

```json
{
  "protocolVersion": 2,
  "type": "HEARTBEAT",
  "messageId": "e933e096-3ddf-45a7-a27e-a30a30d829ee",
  "correlationId": null,
  "agentId": "receiver-1",
  "role": "RECEIVER",
  "sessionId": null,
  "occurredAt": "2026-09-04T02:17:57.277580Z",
  "payload": {}
}
```

메시지 종류:

`HELLO`, `HELLO_ACK`, `HEARTBEAT`, `COMMAND`, `COMMAND_ACK`, `STATUS`, `PORT_LIST`, `INPUT_CHUNK`, `INPUT_COMPLETE`, `FRAME_EVIDENCE`, `ROLE_RESULT`, `ERROR`

명령 종류:

`LIST_PORTS`, `START_CAPTURE`, `STOP_CAPTURE`, `ARM_RECEIVER`, `START_SENDER`, `CANCEL_SESSION`

## 11. 화면 경로

아래는 REST API가 아니라 HTML 화면입니다.

| 경로 | 설명 |
|---|---|
| `/` | AFS Sender로 redirect |
| `/lnis/afstest/sender` | AFS Sender |
| `/lnis/afstest/receiver` | AFS Receiver |
| `/lnis/test/sender` | 기존 Sender 호환 주소 |
| `/lnis/test/receiver` | 기존 Receiver 호환 주소 |
| `/lnis/dtntest/sender` | DTN 송수신 및 PVT 비교 화면 |

## 12. 일반 사용 순서

GRAW 파일 시험:

```text
GET  /agents
POST /inputs
PUT  /inputs/{id}/chunks/0...N
POST /inputs/{id}/complete
POST /sessions
GET  /sessions/{id}
GET  /sessions/{id}/artifacts/...
```

GNSS 수집 후 시험:

```text
POST /agents/{sender}/serial-ports/refresh
POST /captures
POST /captures/{id}/stop
POST /captures/{id}/complete
POST /sessions
```

## 13. DTN AFS 전달 / PVT 비교 API

기존 AFS API와 별도 경로다. COM 수집 종료 후 전송 버튼을 눌러 시험한다.
I/Q 생성, RF 송수신, 달 환경 모사는 수행하지 않는다.
외부 프로그램이 BPv7 생성/송신/수신/해제를 담당한다. 아래 JSON 자체는 BPv7 wire 형식이 아니다.

### 13.1 외부 담당자에게 전달할 계약

1. LNIS 중앙 서버가 외부 프로그램의 설정된 송신 URL에 JSON을 POST한다.
2. 외부 프로그램은 이 데이터를 DTN으로 전달한다.
3. 외부 수신부가 동일 JSON을 LNIS 중앙 서버의 수신 URL에 POST한다.
4. 중앙 서버가 별도 Receiver Agent에 전달하고, Receiver가 AFS 복호화와 PVT 계산을 수행한다.

외부 송신 접수 경로 제안: `POST /lnis-dtn/api/v1/transfers`.
실제 경로는 `LNIS_DTN_SEND_URL`로 설정하므로 외부 담당자의 경로에 맞출 수 있다.
송신 시스템은 HTTP 2xx로 접수를 알린다. 접수 성공은 DTN 전달 완료를 의미하지 않는다.
HTTP 송신 대기 제한은 30초이며 자동 재전송은 하지 않는다.
BPv7 source/destination EID, lifetime, convergence layer 등은 외부 프로그램의 설정으로 관리한다.
현재 JSON에는 그 값을 중복 포함하지 않는다.

양방향 Content-Type은 `application/json`, 문자 인코딩은 UTF-8이다.
송신 시 선택적으로 `Authorization: Bearer <LNIS_DTN_SEND_TOKEN>`을 보낸다.
수신 callback에는 `Authorization: Bearer <LNIS_DTN_RECEIVE_TOKEN>`이 필수다.
callback URL과 인증 토큰을 payload에 싣지 않는다.

### 13.2 외부 전달 JSON

다음은 구조 설명용이며 축약한 Base64와 해시는 실제 요청으로 사용할 수 없다.

```json
{
  "schemaVersion": 1,
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "profile": "POCKETSDR-GPS-L1CA-SPP-v1",
  "format": "LNIS-GRAW-AFS-v1",
  "sourceSha256": "<length-prefixed GRAW 원본의 SHA-256 대문자 hex 64자리>",
  "recordCount": 100,
  "prn": 1,
  "frames": [
    {
      "index": 0,
      "week": 2400,
      "afsItow": 83,
      "toi": 33,
      "frameBase64": "<정확히 750 byte AFS frame의 Base64>"
    }
  ]
}
```

| 필드 | 의미 |
|---|---|
| schemaVersion | 현재 1 |
| testId | 중앙 서버가 발급한 이번 시험 UUID |
| profile | 양쪽 PVT 계산 설정과 지원 신호를 지정하는 불변 프로파일 |
| format | 기존 LNIS GRAW fragment를 AFS SB3/SB4에 넣는 응용 데이터 형식 |
| sourceSha256 | 복원된 length-prefixed GRAW 바이트열 검증용 해시 |
| recordCount | 전체 원본 GRAW 레코드 수 |
| prn | AFS SB2 시험 프로파일 PRN. GPS 관측 위성의 PRN과 다르다. 현재 1 |
| frames | 순서대로 보관한 모든 AFS 프레임 |
| index | 0부터 시작하는 연속 번호 |
| week / afsItow / toi | AFS 프레임 시간 좌표. afsItow는 1,200초 구간 번호 |
| frameBase64 | 750바이트의 부호화된 프레임. 문자열 길이는 1,000자 |

관측 시각(week/TOW), RAWX 관측값, SFRBX 항법 메시지와 수신기 정보는
AFS 프레임 안의 GRAW에 포함한다. 외부 프로그램은 이 내용을 해석할 필요가 없다.
SB2의 고정 LANS 알마낙을 지구 GPS PVT의 항법정보로 사용하지 않는다.
기준 PVT는 외부에 보내지 않는다.

JSON 필드 순서와 공백은 바뀌어도 되지만 필드, 값, 배열 순서는 유지해야 한다.
수신 API는 송신 JSON과 구조적으로 동일한지 확인한다. 필드 추가도 거부한다.
수집 원본은 최대 1 MiB, callback JSON은 최대 16 MiB이다.
외부 시스템의 번들 최대 크기가 더 작으면 연동 전에 분할 계약을 추가해야 한다.

### 13.3 수신 callback

```http
POST /lnis/api/v1/dtn/receive
Content-Type: application/json
Authorization: Bearer <수신용 토큰>
```

본문: 송신받았던 동일 JSON.

```json
{
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "accepted": true,
  "state": "WAITING_RECEIVER"
}
```

HTTP 202는 검증 및 중앙 DB 저장 완료를 뜻하며 PVT 계산 완료를 뜻하지 않는다.
Receiver가 연결되지 않았으면 대기하며 READY가 되면 전달한다.
같은 callback의 중복 도착은 재계산하지 않고 기존 상태를 반환한다.
변경된 JSON은 400, 수신 대기 상태가 아닌 신규 callback은 409,
인증 실패는 401, JSON 크기 초과는 413이다.
시험을 찾을 수 없으면 현재 서비스의 오류 규칙에 따라 400을 반환한다.

### 13.4 화면용 제어 API

- `GET /lnis/api/v1/dtn/config`: 외부 연동 설정 여부, 계산 프로파일, 입력 크기 상한.
- 수집 시작: 기존 `POST /lnis/api/v1/captures` 사용.
- `POST /lnis/api/v1/dtn/captures/{id}/stop?senderAgentId=sender-1`: 마지막 청크 전달까지 기다리는 수집 종료 명령.
- 브라우저는 해당 수집 ID의 GNSS_STATUS/Stopped 이벤트 확인 후 기존 `POST /captures/{id}/complete`로 확정한다.
- `POST /lnis/api/v1/dtn/tests`: 수집 완료 입력으로 기준 계산, AFS 생성 및 외부 전송을 시작한다.
- `GET /lnis/api/v1/dtn/tests/{id}`: 상태 요약.
- `GET /lnis/api/v1/dtn/tests/{id}/report`: 기준/수신 PVT, 관측 시각별 비교 JSON.

시험 시작 요청:

```json
{
  "inputId": "b191cc34-1f80-4b7f-8644-822d3d014d8a",
  "senderAgentId": "sender-1",
  "receiverAgentId": "receiver-1"
}
```

시험 상태:
`PREPARING → WAITING_DTN → WAITING_RECEIVER → CALCULATING → COMPLETED/INCONCLUSIVE`.
각 단계 실패는 FAILED로 기록한다. 시험 전체 제한 시간은 10분이다.
동시에 하나의 DTN 시험만 허용한다.

### 13.5 PVT 의미와 판정

현재 계산 프로파일은 GPS L1 C/A 단독 측위다. 다중 GNSS 전체 지원을 의미하지 않는다.
PocketSDR-AFS 일반 GNSS 경로와 같은 RTKLIB pntpos(), 고도각 15도,
방송 전리층 모델, Saastamoinen 대류권 보정을 사용한다.
관측 데이터 순서대로 항법정보를 갱신하며, DTN 도착 시각으로 관측 시각을 대체하지 않는다.

P는 지구 중심 지구 고정 좌표(ECEF), 단위 m.
V는 ECEF 속도, 단위 m/s. T 비교 항목은 수신기 시계 오차, 단위 s.
관측 시각은 GPS week와 TOW(s)로 별도 제공한다.
계산 실패는 positionValid=false이며, 속도 해가 없으면 velocityValid=false다.
이때 해당 좌표/속도는 null이며 정상적인 0으로 표시하지 않는다.

동일 관측 시각에 대해 위치 차이 0.001 m 이하, 속도 차이 0.001 m/s 이하,
시계 오차 차이 1e-9 s 이하를 일치로 판정한다.
양쪽 유효성 차이나 임계값 초과는 FAIL.
비교 가능한 위치 또는 속도 해가 없으면 INCONCLUSIVE.
이 판정은 전달 전후 계산 일치성이지 절대 위치 정확도 인증이 아니다.

### 13.6 운영 설정

운영 폴더의 .env에 설정한 뒤 서버를 재시작한다.

```dotenv
LNIS_DTN_SEND_URL=http://<외부-DTN-송신부>:<port>/lnis-dtn/api/v1/transfers
LNIS_DTN_SEND_TOKEN=<외부에서 요구하는 경우 송신 인증 토큰>
LNIS_DTN_RECEIVE_TOKEN=<LNIS가 발급해 외부 수신부에 전달할 토큰>
```

외부 담당자에게 알릴 callback 주소:
`http://192.168.1.72:8088/lnis/api/v1/dtn/receive`.
IP는 실제 중앙 서버 주소에 맞춰 변경한다.
Receiver PC의 주소를 callback으로 지정하지 않는다.
외부 DTN 미연결 상태의 시험용 왕복 서버는 실제 BPv7 동작을 검증하지 않는다.

### DTN 역할별 화면 및 최근 시험 조회

- Sender 화면: `/lnis/dtntest/sender` — 수집 및 전송 제어
- Receiver 화면: `/lnis/dtntest/receiver` — Agent 상태, 수신 및 PVT 비교 결과 조회
- `GET /lnis/api/v1/dtn/tests`: 생성 시각 내림차순 최근 50개 시험 요약 배열.
  개별 시험 조회와 같은 필드에 `senderAgentId`, `receiverAgentId`를 포함한다.
  원본 프레임과 전체 PVT는 포함하지 않는다. 상세 결과는 기존 report API를 사용한다.
- Receiver 화면은 2초마다 조회하며 수집·전송·계산 명령을 실행하지 않는다.
  별도 PC에서도 중앙 서버에 저장된 시험을 조회할 수 있다.
