/**
 * GNSS/TX/RX/세션 상태를 브라우저 WebSocket 구독자에게 실시간 방송한다.
 *
 * <p>개별 브라우저 연결 실패가 실제 시험 처리를 중단시키지 않도록 시험 실행과 상태 방송의 오류 경계를 분리한다.
 */
package server.central.realtime;
