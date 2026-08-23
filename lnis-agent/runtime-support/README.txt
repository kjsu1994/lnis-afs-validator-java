LNIS Agent 전용 Java 런타임 폴더
================================

이 폴더의 jdk-21 하위에는 Windows x64 Temurin 21 JRE가 위치합니다.
start-sender-agent.bat과 start-receiver-agent.bat은 이 런타임만 사용합니다.
시스템 PATH, JAVA_HOME 및 기존 Java 17 설치에는 영향을 주지 않습니다.

jdk-21 폴더가 없다면 다음 명령으로 공식 Adoptium JRE를 준비할 수 있습니다.

powershell -ExecutionPolicy Bypass -File .\runtime\install-java21.ps1
