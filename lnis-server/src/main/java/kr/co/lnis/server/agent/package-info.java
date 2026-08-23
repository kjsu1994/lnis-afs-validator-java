/**
 * Windows Agent의 인증된 WebSocket 연결, 상태 저장, 명령 전송과 heartbeat 감시 기능이다.
 *
 * <p>브라우저나 다른 도메인은 Agent WebSocket 구현에 직접 접근하지 않고 이 기능의 service를 통해
 * 명령을 요청한다. Agent 상태는 Redis에 24시간 TTL로 저장된다.
 */
package kr.co.lnis.server.agent;
