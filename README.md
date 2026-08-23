# LNIS AFS Validator 웹 시스템

기존 .NET 8 WPF 기반 **LNIS AFS Validator**의 GRAW 수집, AFS 부호화/복호화, UDP 송수신 및 Test A~E 시험 로직을 다음 구성으로 이전한 프로젝트입니다.

- 중앙 백엔드: Java 21, Spring Boot REST API 및 WebSocket
- 프론트엔드: HTML, CSS, Vanilla JavaScript
- 실시간 상태: WebSocket을 통한 GNSS/TX/RX/시험 결과 전달
- 임시 데이터: Redis 휘발성 버퍼, TTL 24시간
- 시험 PC: Windows Java Agent 및 네이티브 AFS 코덱 DLL
- 배포: Docker Compose 및 Nginx, 외부 서비스 포트 `8088`
- 빌드: Gradle Kotlin DSL

이 문서는 중앙 서버와 Sender/Receiver PC를 처음 설치하는 과정부터 GRAW 시험, 결과 다운로드, API 사용법 및 장애 대응까지 설명합니다.

## 1. 핵심 설계

### 1.1 Spring Boot API 구조

Spring Boot는 화면을 직접 생성하는 서버가 아니라 다음 역할을 수행하는 **중앙 REST API 및 WebSocket 서버**입니다.

- `/lnis/api/v1/**`: Agent 조회, GRAW 입력, GNSS 수집, 시험 실행, 결과 다운로드 REST API
- `/lnis/agent/ws`: Windows Agent 제어 및 Agent 상태 수신 WebSocket
- `/lnis/ws/status`: 브라우저에 GNSS/TX/RX/시험 상태를 전달하는 WebSocket
- Redis에 시험 입력, 상태, 결과를 임시 저장
- Sender와 Receiver Agent의 실행 순서 및 단일 시험 세션 조정

Nginx는 `8088` 포트에서 정적 화면과 Spring Boot 요청을 하나의 주소로 제공합니다. Spring Boot 컨테이너의 `8080` 및 Redis의 `6379` 포트는 호스트에 공개되지 않습니다.

### 1.2 COM 포트를 Windows Agent가 담당하는 이유

웹 브라우저와 Docker 컨테이너가 아니라 **GNSS 장치가 연결된 Windows PC의 Java Agent**가 COM 포트를 담당합니다.

- 일반 브라우저는 임의의 COM 포트에 안정적으로 접근할 수 없습니다.
- 중앙 서버 컨테이너에서는 다른 PC에 연결된 USB/COM 장치를 직접 제어할 수 없습니다.
- Agent에서 COM 연결, u-blox 설정, 원시 메시지 파싱을 수행하면 장치가 연결된 PC에서 장애를 진단하기 쉽습니다.
- 프론트엔드는 Agent에 직접 접근하지 않고 중앙 API에 명령을 요청합니다.

### 1.3 전체 구성

```text
사용자 브라우저
  ├─ /lnis/test/sender
  └─ /lnis/test/receiver
          │ HTTP / WebSocket :8088
          ▼
      Nginx :8088
          ├─ 정적 HTML/JS/CSS
          └─ Spring Boot :8080
                    │
                    ├─ Redis (24시간 TTL, 영구 저장 없음)
                    ├─ WebSocket ─ Sender Windows Agent ─ COM/u-blox
                    └─ WebSocket ─ Receiver Windows Agent

Sender Windows Agent ═════ UDP 45821/45822 ═════ Receiver Windows Agent
```

Sender와 Receiver 사이의 실제 시험 프레임은 중앙 서버를 경유하지 않고 UDP로 직접 전송됩니다. 중앙 서버는 명령, 진행 상태, 입력 전달 및 결과 보관을 담당합니다.

## 2. 프로젝트 디렉터리

| 경로 | 설명 |
|---|---|
| `lnis-protocol` | 서버/Agent 공유 protocol, LGRW/LAFS wire 계약, CRC32, 결정론적 Drop 로직 |
| `lnis-server` | Spring Boot Controller/DTO/Entity/Service/Repository/Mapper 및 Redis 연동 |
| `lnis-agent` | Windows COM/u-blox, JNA 네이티브 코덱, UDP Sender/Receiver |
| `lnis-web` | Sender/Receiver HTML, JavaScript, CSS 및 Nginx 설정 |
| `native` | `LnisAfsCodec.dll`, C ABI 헤더/소스 및 고지 파일 |
| `dist/server` | 빌드된 Spring Boot 실행 JAR |
| `dist/windows-agent` | Windows Agent 설치용 전체 배포본 |
| `docker-compose.yml` | Redis, Spring Boot, Nginx 실행 정의 |

### 2.1 `lnis-protocol`을 독립 모듈로 두는 이유

`lnis-protocol`은 일반적인 유틸리티 모음이 아니라 중앙 서버와 Windows Agent 사이의 **공유 계약**입니다.

```text
lnis-server ──┐
              ├──> lnis-protocol
lnis-agent  ──┘
```

- Spring Boot, Redis, COM 포트, JNA 같은 실행 환경 의존성을 포함하지 않습니다.
- WebSocket envelope, 세션/결과 모델, canonical GRAW와 UDP binary 규격만 제공합니다.
- 서버가 Agent의 Windows 전용 라이브러리를 의존하거나 Agent가 Spring Boot를 의존하지 않게 합니다.
- protocol 변경 시 서버와 Agent가 함께 컴파일되므로 양쪽 규격 불일치를 조기에 발견할 수 있습니다.
- 빌드 시 `lnis-protocol-1.0.0.jar`가 서버 JAR과 Agent 배포본에 각각 포함됩니다.

따라서 세 모듈은 별도 저장소가 아니라 하나의 Gradle 멀티모듈 프로젝트이며, 실제 실행 산출물은 서버와 Agent 두 종류입니다.

백엔드는 공통 계층 폴더에 모든 클래스를 모으는 방식이 아니라, 업무 기능을 먼저 나누고 각 기능 아래에 필요한 계층을 배치하는 **기능 우선(Vertical Slice)** 구조입니다.

```text
kr.co.lnis.server
├─ agent
│  ├─ controller
│  ├─ entity
│  ├─ repository
│  ├─ service
│  └─ websocket
├─ input
│  ├─ controller
│  ├─ dto
│  ├─ entity
│  ├─ repository
│  └─ service
├─ capture
│  ├─ controller
│  └─ dto
├─ session
│  ├─ controller
│  ├─ dto
│  ├─ entity
│  ├─ mapper
│  ├─ repository
│  └─ service
├─ artifact
│  ├─ controller
│  └─ service
├─ realtime
│  ├─ service
│  └─ websocket
├─ common
│  └─ exception
└─ config
```

예를 들어 입력 업로드 기능을 변경할 때는 `input` 하위의 Controller/DTO/Entity/Repository/Service만 함께 살펴보면 됩니다. 기능을 공유하는 코드만 `common` 또는 `config`에 두며, Sender와 Receiver 양쪽에 걸친 세션 로직을 역할별로 중복 구현하지 않습니다.

Windows Agent 역시 역할과 기능을 기준으로 분리되어 있습니다.

```text
kr.co.lnis.agent
├─ config       # Agent ID, 역할, 서버, token, DLL 경로
├─ connection   # 중앙 서버 WebSocket 연결과 heartbeat
├─ runtime      # 서버 명령 분배 및 Agent 상태
├─ gnss         # COM 포트, u-blox, canonical GRAW 수집
├─ codec        # JNA 네이티브 AFS 코덱
└─ session
   ├─ afs       # AFS frame 생성, 오류 주입, fragment 복원
   └─ transport # Sender/Receiver UDP 시험
```

각 Java 클래스에는 책임을 설명하는 한글 주석을 두고, 복수의 처리문을 한 줄에 압축하지 않는 형식을 사용합니다.

## 3. 요구사항

### 3.1 중앙 서버

- Windows/Linux 서버 또는 개발 PC
- Docker Desktop 또는 Docker Engine
- Docker Compose v2
- 외부에서 접근 가능한 TCP `8088`
- 권장 메모리: Redis 최대 메모리 설정값과 Spring Boot/Nginx 실행 메모리를 합산하여 준비

Gradle로 직접 빌드할 경우 Java 21이 필요합니다. Docker Compose만 사용할 경우 호스트 JDK는 필요하지 않습니다.

### 3.2 Sender/Receiver Agent PC

- Windows x64
- Java 21 x64
- WinSW x64(Windows 서비스 설치 시)
- 중앙 서버의 TCP `8088`에 접근 가능
- Sender와 Receiver 간 UDP 통신 가능
- 기본 UDP 포트:
  - 데이터: `45821`
  - 결과: `45822`
- GNSS 수집 PC에는 사용 가능한 COM 포트 및 u-blox 수신기

### 3.3 네트워크/방화벽

- 중앙 서버 인바운드: TCP `8088`
- Receiver 인바운드: UDP 데이터 포트 `45821`
- Sender 인바운드: UDP 결과 포트 `45822`
- Agent 아웃바운드: 중앙 서버 TCP `8088`

브로드캐스트가 차단되는 네트워크에서는 화면의 목적지 주소에 Receiver PC의 IPv4 주소를 입력해 유니캐스트로 시험하는 것이 좋습니다.

## 4. 중앙 서버 실행

작업 디렉터리:

```text
C:\Users\honeybadger\Desktop\Lins Java
```

관리자 권한이 아닌 일반 PowerShell에서 다음 명령을 실행할 수 있습니다.

```powershell
Set-Location 'C:\Users\honeybadger\Desktop\Lins Java'
Copy-Item .env.example .env
```

`.env`에서 기본 토큰을 충분히 긴 임의 문자열로 변경합니다.

```dotenv
LNIS_AGENT_TOKENS=sender-1=sender용-긴-임의-토큰,receiver-1=receiver용-긴-임의-토큰
REDIS_MAXMEMORY=4gb
```

주의 사항:

- `sender-1`, `receiver-1`은 Agent 설정의 `lnis.agent.id`와 정확히 같아야 합니다.
- 쉼표는 Agent 항목 구분자, 등호는 Agent ID와 토큰 구분자입니다.
- `.env`에는 인증 토큰이 있으므로 Git에 커밋하지 않습니다.
- 토큰을 변경하면 해당 Agent 서비스의 설정도 변경한 뒤 재시작해야 합니다.

서비스를 빌드하고 실행합니다.

```powershell
docker compose up -d --build
docker compose ps
```

정상 상태에서는 `redis`와 `server`가 `healthy`, `nginx`가 `Up`으로 표시됩니다.

접속 주소:

- Sender 화면: `http://localhost:8088/lnis/test/sender`
- Receiver 화면: `http://localhost:8088/lnis/test/receiver`
- Agent API: `http://localhost:8088/lnis/api/v1/agents`
- Health: `http://localhost:8088/lnis/api/v1/actuator/health`
- Readiness: `http://localhost:8088/lnis/api/v1/actuator/health/readiness`

다른 PC에서 접속할 때는 `localhost`를 중앙 서버 IP로 바꿉니다.

```text
http://192.168.0.10:8088/lnis/test/sender
```

### 4.1 운영 명령

```powershell
# 상태 확인
docker compose ps

# 전체 로그 추적
docker compose logs -f

# Spring Boot 로그만 추적
docker compose logs -f server

# 서비스 재시작
docker compose restart

# 컨테이너 중지 및 제거
docker compose down

# 소스 변경 후 재빌드
docker compose up -d --build
```

`docker compose down`은 컨테이너와 Compose 네트워크를 제거합니다. 이 프로젝트의 Redis는 디스크 영구 저장을 사용하지 않으므로 컨테이너가 제거되면 남아 있던 시험 데이터는 복구할 수 없습니다.

## 5. Gradle 빌드 및 테스트

이 프로젝트는 Maven이 아니라 **Gradle Kotlin DSL**을 사용합니다.

```powershell
Set-Location 'C:\Users\honeybadger\Desktop\Lins Java'
.\gradlew.bat clean test
.\gradlew.bat :lnis-server:bootJar :lnis-agent:installDist
```

주요 산출물:

- 서버 JAR: `lnis-server/build/libs/lnis-server.jar`
- Agent 배포본: `lnis-agent/build/install/lnis-agent`

호스트에 Java 21이 없으면 Gradle Docker 이미지로 테스트할 수 있습니다.

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace `
  gradle:8.14.3-jdk21 gradle --no-daemon clean test
```

## 6. Windows Agent 설치

Sender와 Receiver PC에 각각 Agent를 설치합니다. 동일 PC에서 두 역할을 동시에 실행하는 구성은 서비스 ID와 UDP 포트 충돌 가능성이 있으므로 기본 운영 구성으로 권장하지 않습니다.

### 6.1 배포본 복사

중앙 개발 PC의 다음 디렉터리 전체를 각 Windows PC로 복사합니다.

```text
dist\windows-agent
```

또는 새로 빌드한 다음 아래 디렉터리를 복사할 수 있습니다.

```text
lnis-agent\build\install\lnis-agent
```

배포 디렉터리에는 최소한 다음 항목이 있어야 합니다.

```text
bin\
conf\
lib\
native\LnisAfsCodec.dll
runtime\jdk-21\bin\java.exe
service\
start-sender-agent.bat
start-receiver-agent.bat
stop-local-agents.ps1
```

`runtime\jdk-21`은 Agent 전용 Windows Java 21 런타임입니다. 시스템에 설치된 Java 17을
삭제하거나 `PATH`, 시스템 `JAVA_HOME`을 변경하지 않습니다. 두 역할별 실행 스크립트도
배포본 안의 Java만 사용하므로 기존 Java 17 서버와 동시에 실행할 수 있습니다.

런타임이 빠진 배포본을 다시 준비할 때는 다음 스크립트를 실행합니다. 스크립트는 공식
Eclipse Adoptium API에서 Windows x64 Temurin 21 JRE를 내려받고 SHA-256을 검증합니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\runtime\install-java21.ps1
```

### 6.2 콘솔 모드로 먼저 확인

서비스 설치 전에 콘솔에서 연결을 검증하는 것이 좋습니다.

Sender PC의 `conf\agent.properties` 예시:

```properties
lnis.agent.id=sender-1
lnis.agent.role=SENDER
lnis.server.ws=ws://192.168.0.10:8088/lnis/agent/ws
lnis.agent.token=중앙서버-env의-sender-token
lnis.native.dir=native
```

Receiver PC 예시:

```properties
lnis.agent.id=receiver-1
lnis.agent.role=RECEIVER
lnis.server.ws=ws://192.168.0.10:8088/lnis/agent/ws
lnis.agent.token=중앙서버-env의-receiver-token
lnis.native.dir=native
```

한 PC에서 로컬 통합 시험을 할 때는 Agent 배포 디렉터리에서 PowerShell 창을 두 개 열고
역할별 스크립트를 하나씩 실행합니다.

```powershell
.\start-sender-agent.bat
```

```powershell
.\start-receiver-agent.bat
```

각 스크립트는 `conf\agent-sender.properties` 또는 `conf\agent-receiver.properties`를
자동 선택합니다. Sender와 Receiver를 서로 다른 PC에 배치하면 각 배포본의 설정 파일에서
`localhost`를 중앙 서버 PC의 실제 IPv4 주소로 변경합니다. 화면의 Agent 상태가 `READY`로
바뀌면 중앙 서버 인증 및 WebSocket 연결이 정상입니다. 종료는 각 창에서 `Ctrl+C`입니다.

백그라운드로 실행한 로컬 Agent 두 개를 종료할 때는 다음 스크립트를 사용합니다. 다른 Java
프로세스는 건드리지 않고 현재 배포본의 Java 21로 실행된 LNIS Agent만 종료합니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-local-agents.ps1
```

설정은 다음 우선순위로 읽습니다.

1. 환경 변수
2. Java 시스템 속성
3. `conf/agent.properties`
4. 코드 기본값

사용 가능한 환경 변수는 `LNIS_AGENT_CONFIG`, `LNIS_AGENT_ID`, `LNIS_AGENT_ROLE`,
`LNIS_SERVER_WS`, `LNIS_AGENT_TOKEN`, `LNIS_NATIVE_DIR`입니다.

### 6.3 Windows 서비스 설치

1. WinSW 공식 릴리스에서 x64 실행 파일을 내려받습니다.
2. Agent 배포 디렉터리를 변경되지 않을 고정 경로에 둡니다.
3. 관리자 PowerShell에서 설치 스크립트를 실행합니다.

Sender 예시:

```powershell
Set-Location 'C:\LNIS\sender-agent'
.\service\install-service.ps1 `
  -Role SENDER `
  -AgentId sender-1 `
  -ServerHost 192.168.0.10 `
  -Token '중앙서버-env의-sender-token' `
  -WinSwExe 'C:\Tools\WinSW-x64.exe'
```

Receiver 예시:

```powershell
Set-Location 'C:\LNIS\receiver-agent'
.\service\install-service.ps1 `
  -Role RECEIVER `
  -AgentId receiver-1 `
  -ServerHost 192.168.0.10 `
  -Token '중앙서버-env의-receiver-token' `
  -WinSwExe 'C:\Tools\WinSW-x64.exe'
```

스크립트는 다음 작업을 수행합니다.

- WinSW 실행 파일을 `service\lnis-agent-service.exe`로 복사
- `conf\agent.properties` 생성
- `runtime\jdk-21\bin\java.exe`를 서비스 실행 파일로 사용
- `LNIS AFS Agent` 서비스 설치 및 시작
- 부팅 시 자동 시작 설정
- 장애 시 5초 후 재시작
- 10MiB 기준 롤링 로그, 최대 14개 보관

서비스 제거:

```powershell
.\service\uninstall-service.ps1
```

### 6.4 Agent 인증

Agent는 `/lnis/agent/ws` 연결 시 Agent ID와 토큰으로 인증합니다. 다음 조건 중 하나라도 다르면 연결되지 않습니다.

- `.env`의 Agent ID와 Agent 설정의 ID가 다름
- `.env` 토큰과 Agent 설정 토큰이 다름
- 동일 ID의 Agent가 잘못된 역할로 구성됨
- 중앙 서버 주소 또는 `8088` 포트 접근 불가

폐쇄망이라도 기본 토큰 `change-me-*`를 운영 환경에서 사용하지 마십시오.

## 7. GNSS 연결 및 GRAW 준비

시험 입력은 다음 두 방법으로 준비할 수 있습니다.

### 7.1 기존 `.graw` 파일 업로드

1. Sender 화면을 엽니다.
2. `GRAW 업로드` 탭을 선택합니다.
3. `.graw` 파일을 선택하고 업로드합니다.
4. 화면에서 record 수, byte 크기 및 SHA-256을 확인합니다.

브라우저는 파일을 `1 MiB` 청크로 나누어 전송합니다. 서버는 모든 청크를 받은 뒤 다음 항목을 검사합니다.

- 선언 크기와 수신 크기 일치 여부
- length-prefixed LGRW record 구조
- record 개수
- 전체 입력 SHA-256

검증 완료 전의 입력으로는 시험을 시작할 수 없습니다.

### 7.2 COM 포트에서 GNSS 수집

1. Sender Agent가 `READY`인지 확인합니다.
2. Sender 화면에서 `GNSS 수집` 탭을 선택합니다.
3. Sender Agent를 선택합니다.
4. `COM 포트 새로고침`을 눌러 포트 목록을 요청합니다.
5. COM 포트, baud rate, protocol을 선택합니다.
6. 수집 시작 후 필요한 시간만큼 기다립니다.
7. 수집 종료를 눌러 입력을 완료합니다.

지원 protocol ID:

- `ubx`: UBX checksum을 검사하고 `RXM-RAWX`, `RXM-SFRBX` 메시지를 canonical GRAW로 변환
- `lnis-canonical-v1`: 이미 length-prefixed LGRW 형식인 직렬 입력
- `raw-only`: 직렬 원본 상태 확인용. canonical GRAW가 생성되지 않으므로 AFS 시험 입력으로 완료할 수 없음

u-blox 연결 시 Agent는 다음과 같이 동작합니다.

- `MON-VER`로 수신기 정보를 조회
- 필요한 RAWX/SFRBX 메시지 출력률을 수신기 RAM에서만 임시 활성화
- 가능한 모든 위성군의 원시 메시지를 수집
- 정상 종료 시 변경 전 출력 설정 복원
- Flash에 영구 설정을 저장하지 않음

COM 연결 실패 시 다른 GNSS 도구가 같은 포트를 점유하고 있지 않은지 먼저 확인합니다.

## 8. 시험 실행 방법

### 8.1 실행 전 확인

- Sender/Receiver Agent가 모두 `READY`
- GRAW 업로드 또는 GNSS 수집이 완료됨
- Receiver 방화벽에서 UDP 데이터 포트 허용
- Sender 방화벽에서 UDP 결과 포트 허용
- 목적지 주소가 네트워크 환경과 일치
- 한 번에 하나의 Sender/Receiver 시험만 실행

### 8.2 화면 실행 순서

1. Receiver PC 또는 모니터링 화면에서 `/lnis/test/receiver`를 엽니다.
2. Sender 화면 `/lnis/test/sender`에서 입력 GRAW를 준비합니다.
3. Sender Agent와 Receiver Agent를 선택합니다.
4. 시험 종류와 조건을 입력합니다.
5. 목적지 주소 및 UDP 포트를 확인합니다.
6. `시험 시작`을 누릅니다.
7. 양쪽 화면에서 TX/RX 진행률과 이벤트 로그를 확인합니다.
8. 완료 후 TX/RX 결과의 JSON 또는 CSV 다운로드 버튼을 누릅니다.

중앙 서버는 Receiver를 먼저 대기 상태로 만든 다음 Sender 송신을 시작합니다.

### 8.3 Test A~E

| 시험 ID | 화면 의미 | 주요 옵션 |
|---|---|---|
| `TEST_A_NORMAL` | 오류를 주입하지 않는 정상 송수신 | 기본값 사용 |
| `TEST_B_RANDOM_ERRORS` | Seed 기반 임의 비트 오류 | `errorCount`, `errorSeed` |
| `TEST_C_BURST_ERRORS` | 연속 비트 오류 | `errorCount`, `errorSeed` |
| `TEST_D_SYNC_RECOVERY` | SP 손상 프레임 제외 후 다음 연속 정상 SP 재획득 확인 | `errorCount`, `errorSeed`, `syncDamageInterval` |
| `TEST_E_UDP_DROP` | 결정론적으로 UDP 복제본을 미전송하는 손실 모의시험 | `dropRatePercent`, `dropSeed` |

동일 입력과 동일 Seed를 사용하면 오류 위치와 UDP 복제본 미전송 위치를 재현할 수 있습니다.

### 8.4 전송 기본값

| 설정 | 기본값 | 설명 |
|---|---:|---|
| 목적지 주소 | `127.0.0.1` | 동일 PC 시험은 loopback, 분리 PC 시험은 Receiver IPv4 |
| 데이터 포트 | `45821` | Sender에서 Receiver로 AFS 데이터 전송 |
| 결과 포트 | `45822` | Receiver에서 Sender로 결과 반환 |
| 반복 송신 | `3` | 각 논리 프레임의 datagram 중복 전송 수 |
| 결과 제한 시간 | `30초` | Sender가 Receiver 결과를 기다리는 시간 |
| 종료 유예 | `1000ms` | SESSION_END 후 지연 패킷 수신 유예 |
| Probe 간격 | `1000ms` | 프로토콜 설정값 |

AFS 기본값은 PRN `8`, Custom Message Type `63`입니다.

### 8.5 세션 상태

| 상태 | 의미 |
|---|---|
| `CREATED` | 세션이 생성됨 |
| `WAITING_RECEIVER` | Receiver 준비/수신 대기 |
| `TRANSMITTING` | Sender가 시험 프레임 전송 중 |
| `EVALUATING` | 수신 데이터 복원 및 판정 중 |
| `COMPLETED` | 정상적으로 시험 및 결과 수집 완료 |
| `CANCELLED` | 사용자가 시험 취소 |
| `FAILED` | 실행 오류로 실패 |
| `INCONCLUSIVE` | 충분한 결과가 없어 판정 불가 |

판정값은 `PASS`, `FAIL`, `INCONCLUSIVE`입니다.

Receiver는 복원 결과의 byte 길이, record 수, SHA-256 및 미완성 fragment 여부를 원본 manifest와 비교합니다. Test D는 손상되지 않은 프레임의 동기 재획득 수와 SB2·SB3·SB4 CRC 완전 복호 수를 별도로 평가합니다.

#### Test D의 오픈소스 근거와 범위

원본 WPF의 Native Codec은 프로젝트에 포함된 `LANS-AFS-SIM`과 `PocketSDR-AFS` 코드를 사용합니다.
두 오픈소스를 대조하면 AFS Data Frame은 `SP 68 + SB1 52 + SB2 2400 + SB3 1740 + SB4 1740 = 6000 symbols`로 구성됩니다.
SP 값은 `CC 63 F7 45 36 F4 9E 04 A`이며 마지막 byte는 상위 4bit만 사용합니다.

PocketSDR-AFS의 `sync_frame()`은 허용 오류 수를 `0`으로 호출해 68심볼 SP를 완전 일치시키고,
현재 SP와 6,000심볼 뒤의 다음 SP가 모두 일치할 때 프레임 동기를 인정합니다. 따라서 Test D에서
SP를 1bit만 손상해도 그 프레임을 정상 동기로 인정하지 않는 동작은 원본 오픈소스 설계와
일치합니다. 이것은 LDPC가 보호하는 SB2·SB3·SB4 데이터 영역의 오류 정정과 별개입니다.

웹 Validator는 저장이 끝난 유한한 시험 stream을 검사하므로 마지막 정상 프레임은 다음 SP가
존재하지 않습니다. 이 경계에서는 직전 SP와 정확히 6,000심볼 간격의 쌍을 이루는 경우에도
정상 SP로 인정합니다. 단독으로 우연히 나타난 SP 동일 bit열은 동기로 채택하지 않습니다.

Test D는 다른 시험과 PASS 기준이 다릅니다. 동기 패턴을 손상한 현재 프레임은 Receiver가
정상 프레임으로 오인하지 않고 버려야 하며, 그 다음 정상 동기 패턴부터 다시 찾아 복호화를
계속해야 합니다. 따라서 Test D에서 다음 조건을 모두 만족하면 전체 GRAW SHA-256이 달라도
PASS입니다.

1. Sender가 준비한 UDP 논리 프레임이 Receiver까지 모두 도착합니다.
2. 의도적으로 동기를 손상한 프레임 수가 시험 조건과 같습니다.
3. `연속 정상 SP 재획득 프레임 = 전체 프레임 - 동기 손상 프레임`입니다.
4. `Decoder 처리 프레임 = 전체 프레임 - 동기 손상 프레임`입니다.
5. `SB2·SB3·SB4 CRC 완전 복호 프레임 = 전체 프레임 - 동기 손상 프레임`입니다.

예를 들어 `dummy-capture.graw`는 464 byte, 4 record이고 이 입력에서는 record 한 개가
116 byte입니다. 첫 프레임 한 개의 동기를 손상하면 Receiver는 UDP 논리 프레임 4개를 모두
받지만 손상 프레임은 버립니다. 따라서 `3/4 record`, `348/464 byte`, SHA-256 불일치가 되며,
나머지 `3/3 frame`을 재동기화하고 복호화했다면 정상 PASS입니다. 화면은 이 경우를 빨간
실패가 아니라 `예상 결과`와 `부분 복원`으로 표시합니다.

Receiver는 세션 시작 패킷 또는 다음 패킷을 제한 시간 안에 받지 못하면 자동으로
`INCONCLUSIVE` 결과를 보내고 수신 socket을 종료합니다. 중앙 서버도 Agent 결과 유실에
대비한 watchdog을 실행하며, 입력 크기와 결과 제한 시간을 기준으로 만료된 시험을 자동
취소하고 Redis 활성 시험 잠금을 해제합니다.

Sender 화면은 `GET /lnis/api/v1/sessions/active`로 현재 시험을 주기적으로 확인합니다.
따라서 페이지를 새로 열어도 `진행 중 시험 취소` 버튼이 다시 활성화되며 사용자가 세션 ID나
Redis 잠금을 직접 찾을 필요가 없습니다.

### 8.6 화면 도움말과 수신 진행률

Sender와 Receiver 화면의 모든 버튼, 입력 항목, 연결 상태, 진행률, 판정, 측정값 및
다운로드 링크에는 한글 도움말이 연결되어 있습니다. 마우스를 항목 위에 올리거나 키보드로
포커스를 이동하면 해당 값의 의미와 사용 방법이 표시되며, 열린 도움말은 `Esc` 키로 닫을
수 있습니다. 동적으로 생성되는 결과 측정값과 JSON/CSV 다운로드 링크에도 같은 방식으로
도움말이 자동 적용됩니다.

Receiver 수신 프레임 진행률은 수신 구간에서 최대 80%까지 표시됩니다. 나머지 20%는
프레임 재조립, 원본 크기·레코드 수·SHA-256 무결성 검증과 최종 결과 확정 구간입니다.
Agent 진행 이벤트의 `percent`와 중앙 서버 세션 이벤트의 `progress`를 화면에서 모두
처리하며, Receiver의 `RESULT` 또는 `COMPLETED` 상태가 도착하면 진행률을 100%와
`수신 및 검증 완료` 상태로 표시합니다. 따라서 정상 시험이 화면에서 80%에 머물지 않습니다.

이벤트 로그는 단순한 영문 단계명 대신 다음 상세 정보를 한글로 표시합니다.

- 공통: 세션 상태, 시험 종류, 현재 진행률과 최종 판정
- 송신 준비: 원본 byte 수, GRAW record 수, AFS frame 수, 목적지 IP·포트와 반복 횟수
- Test B/C: 손상된 프레임 번호, Random/Burst 구분과 실제 AFS frame bit 위치
- Test D: 동기 손상 프레임 번호, 동기 영역의 실제 bit 위치와 복구 프레임 수
- Test E: 프레임별 실제 송신 복제본 수와 Sender가 의도적으로 보내지 않은 복제본 번호
- 수신: 현재/예상 frame 수, 수신 datagram 수, 중복 및 손상 datagram 수
- 검증: 원본/복원 byte와 record 수, SHA-256 일치 여부
- 최종 결과: TX/RX 판정, 네트워크 카운터와 복호화·CRC·오류 정정 측정값

결과 카드에도 예상/수신 프레임, 송수신·중복, UDP 해석 실패, AFS 복호화 실패,
주입 비트와 Sender 미전송 데이터그램, 처리 byte,
원본/복원 크기와 record 수 및 SHA-256 비교 결과가 각각 표시됩니다. 화면 로그가 너무
커지지 않도록 한 프레임에서 오류 위치가 40개를 넘으면 앞의 40개와 나머지 개수를 표시하지만,
Agent 이벤트에는 해당 프레임의 전체 위치 목록이 구조화된 값으로 전달됩니다.

오류 관련 수치는 전송 계층과 AFS 계층을 섞지 않고 다음과 같이 분리합니다.

| 화면 항목 | 단위 | 의미 |
|---|---|---|
| 시험에서 주입한 오류 | bit | Test B/C/D 조건에 따라 Sender가 AFS 프레임 내부에서 반전한 비트 총합 |
| 동기 손상으로 제외 | frame | Test D에서 손상된 동기를 정상 동기로 오인하지 않고 제외한 AFS 프레임 수 |
| UDP 패킷 해석 실패 | datagram | LNIS UDP 패킷 구조 또는 패킷 CRC를 해석하지 못해 폐기한 데이터그램 수 |
| AFS 복호화 실패 | frame | 동기를 찾았지만 디코더 예외 또는 SB3/SB4 CRC 실패로 재조립하지 못한 프레임 수 |

예를 들어 Test D에서 `1 bit`를 주입하고 해당 동기 프레임을 정상적으로 제외했다면
`시험에서 주입한 오류 1 bit`, `동기 손상으로 제외 1 frame`, `UDP 패킷 해석 실패
0 datagram`, `AFS 복호화 실패 0 frame`이 정상입니다. 이전의 `손상 패킷` 표현은 서로 다른
단위가 섞여 오해를 만들기 때문에 화면에서 사용하지 않습니다. JSON의 `corruptDatagrams`는
기존 결과 소비자와의 호환을 위해서만 유지하며 새 화면은 분리된 카운터를 사용합니다.

결과 화면은 단순 수치 나열 대신 다음 순서로 표시합니다.

1. 시험 결과 해석: PASS/FAIL 이유와 프레임·크기·레코드·SHA-256 핵심 확인표
2. 원본 복원 결과: 복원값/원본값을 한 카드에서 직접 비교
3. 프레임 처리 결과: 수신값/예상값과 복호화값/수신값을 직접 비교
4. 전송 및 오류 처리 현황: 주입 비트, 동기 제외 frame, UDP 해석 실패 datagram과 AFS 복호화 실패 frame을 구분
5. 전문 진단 지표: SB2/SB3/SB4 CRC 및 LDPC 내부 값은 접힌 영역에서 필요할 때 확인

`CorrectedSymbols` 원시 값은 화면에서 `LDPC 내부 판정 변경량`으로 표시합니다. 이 값은
LDPC 디코더가 초기 판정에서 변경한 누적 비트 수이며 천공·소거 처리의 영향도 받을 수
있으므로, 사용자가 주입한 오류 개수나 실제 채널 오류 개수로 해석하면 안 됩니다.

`sentDatagrams`는 실제 AFS FRAME UDP 패킷만 세지만 `receivedDatagrams`는 Receiver가 받은
SESSION_START 같은 제어 패킷도 포함합니다. 따라서 정상 시험에서도 수신 데이터그램 수가
송신 데이터그램 수보다 클 수 있으며, 화면 설명에도 이 집계 범위 차이를 명시합니다.

상세 로그 포맷 회귀 테스트는 다음 명령으로 실행합니다.

```powershell
node lnis-web\test\event-log.smoke.mjs
```

결과 카드의 일반 시험 및 Test D 전용 표시 회귀 테스트는 다음 명령으로 실행합니다.

```powershell
node lnis-web\test\result-presentation.smoke.mjs
```

### 8.7 sample-data 실제 A~E 회귀시험

`C:\Users\honeybadger\Desktop\Lnis\sample-data\dummy-capture.graw`를 REST API로 실제 업로드하고,
로컬 Sender/Receiver Agent와 UDP 통신을 사용해 A~E를 순서대로 실행하는 스크립트를 제공합니다.
서버, Redis, Nginx와 두 Agent가 실행된 상태에서 다음 명령을 사용합니다.

```powershell
Set-Location 'C:\Users\honeybadger\Desktop\Lins Java'
.\scripts\run-sample-regression.ps1
```

특정 시험만 다시 확인하려면 다음처럼 `TestTypes`를 지정합니다.

```powershell
.\scripts\run-sample-regression.ps1 -TestTypes 'TEST_D_SYNC_RECOVERY'
.\scripts\run-sample-regression.ps1 -TestTypes 'TEST_A_NORMAL','TEST_E_UDP_DROP'
```

스크립트는 단순히 HTTP 200이나 최종 PASS만 검사하지 않습니다.

- 공통: 세션 `COMPLETED`, TX/RX/최종 `PASS`, 논리 프레임 `4/4`, 손상 datagram `0`
- Test A/B/C: 복호화 `4/4`, record `4/4`, byte `464/464`, SHA-256 일치
- Test D: 복호화·재동기 `3/3`, record `3/4`, byte `348/464`, SHA-256 불일치가 예상 결과인지 확인
- Test E: Sender가 실제로 미전송한 복제본이 1건 이상이면서도 `4/4`, `464/464`, SHA-256 일치인지 확인
- 프레임 증거: 4개 프레임의 Sender/Receiver 증거 병합, 단계별 750 byte 원문, 송신/수신 차이 0,
  복호화 후 재인코딩과 기준 프레임 일치 여부를 확인
- Test D 프레임 증거: 동기 손상 프레임은 `intentionalSyncRejection=true`이고 재인코딩 자료가 없으며,
  나머지 3개 프레임은 기준 대비 0 bit인지 확인

2026-08-23 로컬 loopback 실측에서는 A/B/C/E가 모두 `4/4 record`, `464/464 byte`,
SHA-256 일치로 PASS했습니다. Test D는 설계대로 `3/4 record`, `348/464 byte`, SHA-256
불일치이면서 재동기·복호화 `3/3`으로 PASS했습니다. Test E는 반복 송신 5회, 복제본
미전송 확률 30%, Seed 1 조건에서 3개 복제본을 실제로 보내지 않았지만 원본을 완전히 복원했습니다.

## 9. 결과 파일

TX와 RX 각각 다음 파일을 HTTP attachment로 내려받을 수 있습니다.

| 파일 | 내용 |
|---|---|
| `result.json` | 판정, 무결성 결과, 지표, 네트워크 카운터, 자원 샘플, 오류 정보 |
| `metrics-summary.csv` | Role/Category/Name/Description/Value/Unit/Status 요약 |
| `metrics-timeseries.csv` | Timestamp별 CPU 및 Working Set 메모리 |
| `frame-evidence.json` | 보관된 프레임별 기준/송신/수신/재인코딩 6,000비트 원문, SHA-256, 차이 위치 |
| `frame-diff-summary.csv` | 프레임별 오류 주입·전송 중 변화·최종 복구 비트 차이 수와 해시 요약 |

CSV는 Excel 한글 호환성을 위해 UTF-8 BOM을 포함합니다. 실제 다운로드 파일명에는 session ID와 `tx` 또는 `rx`가 포함됩니다.

예:

```text
<session-id>-tx-result.json
<session-id>-rx-metrics-summary.csv
<session-id>-rx-metrics-timeseries.csv
<session-id>-frame-evidence.json
<session-id>-frame-diff-summary.csv
```

결과 파일은 미리 서버 디스크에 생성하지 않습니다. 사용자가 다운로드할 때 Redis의 결과 객체를 CSV 또는 JSON byte stream으로 생성합니다. 현재 구현은 `reconstructed.graw` 파일을 산출하지 않습니다.

### 9.1 AFS 6,000비트 점자형 지도 읽는 방법

시험 결과 아래의 **6,000비트 프레임 직접 비교** 영역은 AFSFrame 하나를 100열 × 60행으로
표시합니다. 작은 사각형 하나가 AFS 심볼 1개이므로 한 지도에 정확히 6,000개가 들어갑니다.
진한 점과 옅은 점은 각각 비트 1과 0이고, 빨간 점은 해당 단계의 비교 기준과 다른 위치입니다.

화면은 다음 네 단계를 같은 좌표로 보여줍니다.

1. **기준 AFSFrame**: GRAW를 정상 인코딩했으며 아직 시험 오류를 넣지 않은 기준값
2. **실제 송신 AFSFrame**: Test B/C/D 오류를 주입한 뒤 Sender가 실제 UDP에 넣은 값
3. **Receiver 수신 AFSFrame**: UDP 구조와 CRC32 검사를 통과해 Receiver가 채택한 값
4. **복호화 후 재인코딩 검증**: Receiver가 복호화한 SB2/SB3/SB4를 같은 TOI로 다시 인코딩한 값

지도 위에 마우스를 올리면 같은 심볼 좌표가 네 지도에서 함께 강조되고, 해당 위치의 단계별
0/1 값이 표시됩니다. **최종 복구 차이 0 bit**는 네 번째 검증 프레임이 첫 번째 기준 프레임과
6,000비트 전부 같다는 뜻입니다. 단, 이는 프레임 수준 증거이며 최종 PASS는 GRAW 크기,
record 수, SHA-256과 시험별 판정 조건도 함께 충족해야 합니다.

`AFS Decoder 처리`는 Native Decoder 호출이 예외 없이 끝난 횟수이며 복구 성공 횟수가 아닙니다.
실제 완전 복호 성공은 별도의 `CRC까지 통과한 완전 복호`에서 확인합니다. 프레임별 완전 복호는
SB2·SB3·SB4 CRC가 모두 정상일 때만 인정합니다. 비트맵의 **프레임별 복호 진단**에는 다음 정보가
표시됩니다.

최종 시험이 `FAIL`이면 6,000비트 비교 화면은 첫 번째 복구 실패 프레임을 자동으로 선택합니다.
화면의 `전체 시험 판정`은 GRAW 전체 시험의 판정이고, `현재 선택 프레임 판정`은 선택 목록의 프레임
하나만을 대상으로 합니다. 따라서 전체 시험이 `FAIL`이어도 사용자가 완전 복구된 프레임을 선택하면
1번 기준 지도와 4번 재인코딩 지도가 동일할 수 있으며, 이 경우 화면에 해당 범위 차이를 명시합니다.

- Decoder 처리 완료/실패
- SB2·SB3·SB4 CRC 결과
- 블록별 LDPC 내부 판정 변경량(실제 주입 오류 수가 아닌 참고값)
- GRAW 재조립 사용/제외 여부
- CRC 실패 블록 또는 Decoder 예외 원인
- 기준 프레임 대비 재인코딩 잔여 차이

CRC 실패 상태에서도 Decoder가 출력한 SB를 재인코딩한 네 번째 지도는 진단 목적으로 표시될 수
있습니다. 이때 지도에 `CRC 실패 · 진단용` 배지가 표시되며, 네 번째 지도가 존재한다는 사실만으로
복구 성공으로 판단하면 안 됩니다.

Test D에서 동기 패턴을 의도적으로 훼손한 프레임은 Receiver가 정상 프레임으로 채택하지 않으므로
네 번째 지도가 없을 수 있습니다. 이것은 Test D의 설계된 동작이며, 다음 정상 동기를 찾아낸
프레임들의 복구 결과와 Test D 판정 카드를 함께 확인해야 합니다.

## 10. Redis 저장 정책

Redis는 시험 중 필요한 임시 버퍼로만 사용됩니다.

- RDB snapshot 비활성화: `--save ""`
- AOF 비활성화: `--appendonly no`
- 메모리 정책: `noeviction`
- 입력/세션/결과/Agent 상태 키 TTL: 최대 24시간
- AFS 프레임 상세 증거 TTL: 최대 24시간
- 상세 증거 상한: 세션당 최대 500프레임. 초과 시 처음 250개와 마지막 250개 보관
- Redis 포트는 Docker 내부 네트워크에서만 접근

`noeviction`은 메모리가 부족할 때 오래된 진행 중 시험을 임의로 삭제하지 않고 새로운 쓰기를 실패시키기 위한 선택입니다. 대용량 입력을 사용할 때는 `.env`의 `REDIS_MAXMEMORY`를 조정해야 합니다.

예를 들어 최대 1GB GRAW를 취급한다면 원본 청크, 세션 정보, 결과 및 Redis 오버헤드가 함께 필요하므로 최소값만 1GB로 잡지 말고 서버 가용 메모리를 고려해 여유 있게 설정하십시오. 현재 기본값은 `4gb`입니다.

다음 상황에서는 데이터가 사라질 수 있으며 이는 설계된 동작입니다.

- TTL 24시간 경과
- Redis 컨테이너 삭제/재생성
- Docker 또는 호스트 강제 종료
- 운영자가 키를 삭제

보존이 필요한 결과는 시험 완료 직후 CSV/JSON으로 다운로드하여 별도 저장소에 보관해야 합니다.

프레임 증거는 프레임당 최대 네 개의 750 byte 원문과 JSON/Base64 오버헤드를 사용합니다.
따라서 대용량 시험에서 모든 프레임을 무제한 저장하지 않습니다. 보관되지 않은 중간 프레임은
화면 선택 목록과 `frame-evidence.json`에도 포함되지 않습니다.

## 11. REST API 상세

기본 경로:

```text
http://<server>:8088/lnis/api/v1
```

요청/응답은 별도 표기가 없으면 `application/json`입니다. API 오류는 Spring `ProblemDetail` 형식으로 응답합니다.

### 11.1 Agent

```http
GET /lnis/api/v1/agents
GET /lnis/api/v1/agents/{agentId}
POST /lnis/api/v1/agents/{agentId}/serial-ports/refresh
```

Agent 응답에는 `agentId`, `role`, `state`, `lastSeen`, `version`, `codecAbiVersion`, OS/architecture 및 오류 정보가 포함됩니다.

PowerShell 예시:

```powershell
Invoke-RestMethod 'http://localhost:8088/lnis/api/v1/agents'
Invoke-RestMethod -Method Post `
  'http://localhost:8088/lnis/api/v1/agents/sender-1/serial-ports/refresh'
```

### 11.2 GRAW 입력 업로드

1단계: 입력 생성

```http
POST /lnis/api/v1/inputs
```

```json
{
  "fileName": "capture.graw",
  "size": 1048576,
  "kind": "GRAW_UPLOAD"
}
```

`kind`는 `GRAW_UPLOAD` 또는 `GNSS_CAPTURE`입니다.

2단계: 순서대로 청크 업로드

```http
PUT /lnis/api/v1/inputs/{inputId}/chunks/0
Content-Type: application/octet-stream
```

각 청크의 최대 권장 크기는 화면 구현과 동일한 `1 MiB`입니다. 청크 index는 `0`부터 순차 증가해야 합니다.

3단계: 검증 및 완료

```http
POST /lnis/api/v1/inputs/{inputId}/complete
GET /lnis/api/v1/inputs/{inputId}
DELETE /lnis/api/v1/inputs/{inputId}
```

완료 응답에는 `receivedSize`, `chunkCount`, `recordCount`, `sha256`, `complete` 등이 포함됩니다.

### 11.3 GNSS 수집

```http
POST /lnis/api/v1/captures
```

```json
{
  "senderAgentId": "sender-1",
  "portName": "COM3",
  "baudRate": 115200,
  "protocolId": "ubx",
  "sessionName": "web-capture",
  "receiverModel": "u-blox",
  "firmwareVersion": "auto",
  "dtrEnabled": false,
  "rtsEnabled": false
}
```

baud rate 허용 범위는 `1200`~`4000000`입니다.

```http
POST /lnis/api/v1/captures/{captureId}/stop?senderAgentId=sender-1
POST /lnis/api/v1/captures/{captureId}/complete
```

`stop`은 Agent에 정지 명령을 전달하며, `complete`는 수신된 canonical GRAW를 검증하고 시험 입력으로 확정합니다.

### 11.4 시험 세션

```http
POST /lnis/api/v1/sessions
```

```json
{
  "senderAgentId": "sender-1",
  "receiverAgentId": "receiver-1",
  "inputId": "00000000-0000-0000-0000-000000000000",
  "transport": {
    "broadcastAddress": "192.168.0.21",
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

조회 및 취소:

```http
GET /lnis/api/v1/sessions/active
GET /lnis/api/v1/sessions/{sessionId}
POST /lnis/api/v1/sessions/{sessionId}/cancel
```

활성 세션이 없으면 `/active`는 `204 No Content`를 반환합니다. 개별 세션 조회 응답에는 상태,
진행률, 메시지, 최종 판정, TX 결과 및 RX 결과가 포함됩니다. 취소는 한쪽 Agent가 이미
오프라인이어도 중앙 상태와 Redis 잠금을 우선 정리하고 연결된 나머지 Agent에 계속 전달됩니다.

### 11.5 결과 다운로드

```http
GET /lnis/api/v1/sessions/{sessionId}/artifacts/tx/result.json
GET /lnis/api/v1/sessions/{sessionId}/artifacts/tx/metrics-summary.csv
GET /lnis/api/v1/sessions/{sessionId}/artifacts/tx/metrics-timeseries.csv
GET /lnis/api/v1/sessions/{sessionId}/artifacts/rx/result.json
GET /lnis/api/v1/sessions/{sessionId}/artifacts/rx/metrics-summary.csv
GET /lnis/api/v1/sessions/{sessionId}/artifacts/rx/metrics-timeseries.csv
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/{frameIndex}
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/artifacts/frame-evidence.json
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/artifacts/frame-diff-summary.csv
```

첫 번째 프레임 증거 API는 원문을 제외한 프레임 선택 목록과 SHA-256/차이 수를 반환합니다.
두 번째 API만 선택한 750 byte 프레임 네 단계 원문과 비트 차이 위치를 Base64 JSON으로 반환해
브라우저와 서버가 한 번에 불필요한 대용량 데이터를 주고받지 않게 합니다.

`role`에는 `tx`/`sender` 또는 `rx`/`receiver`를 사용할 수 있습니다.

PowerShell 다운로드 예시:

```powershell
$sessionId = '<실제-session-id>'
Invoke-WebRequest `
  "http://localhost:8088/lnis/api/v1/sessions/$sessionId/artifacts/rx/result.json" `
  -OutFile "rx-result.json"
```

## 12. WebSocket

### 12.1 브라우저 상태 WebSocket

```text
ws://<server>:8088/lnis/ws/status
```

브라우저는 다음 이벤트를 실시간으로 받습니다.

- `AGENT_STATUS`: Agent 접속, 상태 변경
- `GNSS_STATUS`: COM/GNSS 수집 상태
- `TX_STATUS`: Sender 전송 진행률
- `RX_STATUS`: Receiver 수신 및 복호화 진행률
- `SESSION_STATUS`: 세션 상태 변경
- `RESULT`: TX/RX 결과
- `ERROR`: 오류

이벤트 envelope의 주요 필드는 `sequence`, `type`, `occurredAt`, `agentId`, `role`, `sessionId`, `payload`입니다.

### 12.2 Agent 제어 WebSocket

```text
ws://<server>:8088/lnis/agent/ws
```

Agent 프로토콜 버전은 `1`이며 HELLO/HEARTBEAT/COMMAND/STATUS/PORT_LIST/INPUT_CHUNK/ROLE_RESULT 등의 메시지를 교환합니다. 이 경로는 일반 브라우저 UI 용도가 아니며 Agent ID와 토큰 인증이 필요합니다.

운영 서버에 TLS를 적용하면 화면/API는 `https`, WebSocket은 `wss`를 사용하도록 Nginx와 Agent 설정을 함께 변경해야 합니다.

## 13. 상태 확인과 문제 해결

### 13.1 화면이 열리지 않음

```powershell
docker compose ps
docker compose logs --tail=200 nginx
docker compose logs --tail=200 server
```

- `8088` 포트를 다른 프로그램이 사용 중인지 확인합니다.
- 중앙 서버 방화벽의 TCP `8088` 인바운드 규칙을 확인합니다.
- URL에 `/lnis/test/sender` 또는 `/lnis/test/receiver`가 포함됐는지 확인합니다.

### 13.2 Agent가 OFFLINE

- Agent PC에서 `http://<server>:8088/lnis/api/v1/actuator/health` 접근 여부 확인
- Agent의 `lnis.server.ws` 주소 확인
- `.env`와 `agent.properties`의 Agent ID/token 일치 여부 확인
- Agent 서비스 로그 확인
- 서버 로그에서 WebSocket 인증 실패 확인

```powershell
docker compose logs --tail=200 server
Get-Service 'lnis-agent'
```

### 13.3 COM 포트가 표시되지 않음

- Sender Agent가 `READY`인지 확인
- 장치 관리자에서 실제 COM 번호 확인
- 다른 GNSS 프로그램이 포트를 열고 있으면 종료
- USB 드라이버 설치 상태 확인
- 올바른 baud rate 선택
- `COM 포트 새로고침` 후 Agent 이벤트 확인

브라우저에서 COM 포트를 직접 조회하지는 않습니다. 중앙 서버가 다음 API를 통해 선택된
Windows Sender Agent에 `LIST_PORTS` 명령을 보내고, Agent가 조회 결과를 WebSocket으로
화면에 돌려주는 구조입니다.

```text
POST /lnis/api/v1/agents/{agentId}/serial-ports/refresh
```

개발자 도구에 `/agents//serial-ports/refresh`처럼 Agent ID가 비어 있는 URL이 보이면
서버에 연결된 Sender Agent가 없다는 뜻입니다. `/lnis/api/v1/agents` 응답을 확인하고,
Sender PC에서 Agent를 먼저 실행해야 합니다. 화면은 연결된 Agent가 없을 때 선택 상자에
`연결된 Sender Agent 없음`을 표시하고 새로고침 및 수집 시작 버튼을 비활성화합니다.

`POST /lnis/api/v1/captures`가 `400 Bad Request`를 반환하면 응답의 `detail`에 표시된
Sender Agent, COM 포트, baud rate 또는 프로토콜 입력을 확인합니다. 화면에서는 팝업 대신
상단 인라인 알림으로 원인과 조치 방법을 안내합니다. Agent 조회나 명령 전송이 실패한 경우
서버는 생성 중이던 Redis 입력 메타데이터도 즉시 제거합니다.

### 13.4 Receiver가 데이터를 받지 못함

- 목적지 주소를 Receiver PC의 실제 IPv4로 지정해 유니캐스트 시험
- Receiver 방화벽 UDP `45821` 허용
- Sender 방화벽 UDP `45822` 허용
- 두 PC가 동일 라우팅 구간에 있고 UDP가 차단되지 않는지 확인
- 다른 프로그램이 동일 UDP 포트를 사용 중인지 확인
- Sender/Receiver의 data/result port 설정이 같은지 확인

포트 사용 확인 예시:

```powershell
Get-NetUDPEndpoint | Where-Object LocalPort -In 45821,45822
```

### 13.5 Redis 메모리 오류

```powershell
docker compose logs --tail=200 redis
docker compose exec redis redis-cli INFO memory
```

`OOM command not allowed`가 보이면 진행 중이지 않은 입력/세션의 TTL 만료를 기다리거나 서버 가용 메모리를 확인한 뒤 `.env`의 `REDIS_MAXMEMORY`를 늘리고 Compose를 재기동합니다. Redis를 재생성하면 기존 시험 데이터는 사라집니다.

### 13.6 DLL/JNA 오류

- Agent와 Java가 모두 x64인지 확인
- `native\LnisAfsCodec.dll` 존재 여부 확인
- `lnis.native.dir`가 올바른 디렉터리를 가리키는지 확인
- DLL이 요구하는 Visual C++ Runtime이 설치되어 있는지 확인
- 파일이 인터넷에서 내려받은 것으로 차단되었다면 파일 속성에서 차단 해제

## 14. 운영 및 보안 고려사항

현재 구성은 **폐쇄된 시험 LAN**을 전제로 합니다.

- Agent token은 반드시 기본값에서 변경
- `.env` 파일 접근 권한 제한
- 외부망에 `8088` 직접 공개 금지
- 외부망 또는 여러 사용자 환경에서는 Nginx TLS와 사용자 인증 추가
- 운영 로그에 토큰이나 민감한 시험 데이터를 기록하지 않도록 주의
- 시험 직후 필요한 CSV/JSON 다운로드
- 호스트 시간 동기화(NTP) 권장
- 방화벽은 필요한 서버/포트/상대 PC만 허용

REST API에는 현재 별도의 브라우저 사용자 로그인 기능이 없습니다. 신뢰할 수 없는 네트워크에 배포하려면 사용자 인증/인가, HTTPS/WSS, 요청 크기 제한, 감사 로그 및 접근 제어를 추가해야 합니다.

## 15. 현재 구현 범위와 제한

- 동시에 하나의 Sender/Receiver 시험 세션을 기준으로 구현
- Redis 데이터는 영구 보존하지 않음
- 결과는 JSON/CSV로만 제공하며 복원 GRAW 파일은 제공하지 않음
- Agent는 Windows 서비스 배포를 기준으로 함
- COM/u-blox 및 실제 두 PC 간 UDP 동작은 현장 장비와 네트워크에서 최종 인수 시험 필요
- 최대 1GB 입력은 Redis 메모리뿐 아니라 브라우저, Agent JVM heap, 네트워크 시간 및 시험 지속 시간을 포함한 부하 시험 필요

권장 현장 인수 순서:

1. 작은 sample GRAW로 Test A 유니캐스트 시험
2. Broadcast 시험
3. Test B~E 기능 시험
4. 실제 GNSS COM 수집 시험
5. 장시간/대용량 입력 시험
6. Agent 서비스 자동 재시작 및 PC 재부팅 시험
7. 결과 CSV/JSON 보존 및 추적성 확인

## 16. 빠른 시작 요약

중앙 서버:

```powershell
Set-Location 'C:\Users\honeybadger\Desktop\Lins Java'
Copy-Item .env.example .env
# .env의 token 변경
docker compose up -d --build
docker compose ps
```

Agent PC:

```powershell
# dist\windows-agent를 고정 경로에 복사하고 conf\agent.properties 작성
.\bin\lnis-agent.bat
```

시험:

1. `http://<server>:8088/lnis/test/receiver` 열기
2. `http://<server>:8088/lnis/test/sender` 열기
3. 두 Agent가 `READY`인지 확인
4. GRAW 업로드 또는 GNSS 수집
5. Receiver IP와 UDP 포트 설정
6. Test A부터 실행
7. 완료 후 TX/RX JSON 및 CSV 다운로드
