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
| `/lnis/dtntest/sender` | DTN 송수신 시험 빈 화면 |

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
