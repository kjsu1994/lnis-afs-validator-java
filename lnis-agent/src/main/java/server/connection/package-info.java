/**
 * Windows Agent와 중앙 서버 사이의 장기 WebSocket 연결을 관리한다.
 *
 * <p>Agent 인증 header, 최초 HELLO, 주기적 heartbeat, 서버 명령 수신 및 연결 종료 후 재접속을 담당한다.
 * 실제 GNSS/UDP 작업은 수행하지 않고 수신 메시지를 runtime 패키지에 전달한다.
 */
package server.connection;
