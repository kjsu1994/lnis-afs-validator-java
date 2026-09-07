# LNIS 실행 폴더

## 처음 실행

1. WSL과 WSL 내부 Docker를 실행합니다.
2. 필요하면 `.env`의 Sender/Receiver token을 변경합니다.
3. `START.cmd`를 더블클릭합니다.

서버가 준비되면 이 PC의 Sender Agent도 자동 실행되고 브라우저가 열립니다.

## 종료

`STOP.cmd`를 더블클릭합니다. 종료해도 `DB`의 H2와 GRAW 파일은 유지됩니다.

## 백업

PowerShell에서 다음을 실행합니다.

```powershell
.\backup.ps1
```

서버를 안전하게 중지하고 `backups` 아래에 DB와 `.env`를 복사한 다음 서버를 다시 시작합니다.

## 다른 컴퓨터로 복사

서버를 `STOP.cmd`로 먼저 종료한 후 이 폴더 전체를 다른 컴퓨터의 `C:\lnis-compose`로 복사합니다.
새 컴퓨터에도 WSL과 Docker가 필요합니다. Receiver/Sender PC에는 `agent\lnis-agent-windows.zip`을 사용합니다.

Receiver PC에서는 ZIP을 압축 해제한 후 `START-RECEIVER.cmd`를 더블클릭합니다. 중앙 서버 IP나
token이 기본값과 다르면 `config\application-receiver.yml`을 수정합니다. 종료는 `STOP-AGENT.cmd`입니다.

## 개발 빌드 반영

개발 프로젝트에서 다음 명령이 성공하면 이 폴더의 실행 파일이 자동 갱신됩니다.

```powershell
.\gradlew.bat clean build
```

기존 `.env`와 `DB`는 빌드로 덮어쓰지 않습니다.
