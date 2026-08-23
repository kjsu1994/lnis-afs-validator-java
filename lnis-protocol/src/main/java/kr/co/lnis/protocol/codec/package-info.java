/**
 * LNIS의 binary wire format을 담당한다.
 *
 * <p>canonical GRAW 레코드, AFS UDP datagram, CRC32/SHA-256 계산 및 결정론적 Drop 규칙을 제공한다.
 * 이 패키지의 byte 순서와 필드 크기는 서버와 Agent 간 호환성에 직접 영향을 주므로 변경 시 기존
 * 캡처 파일과 UDP packet의 하위 호환성을 반드시 검토해야 한다.
 */
package kr.co.lnis.protocol.codec;
