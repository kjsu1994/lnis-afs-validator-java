/**
 * Sender/Receiver 한 쌍의 시험 생성, 실행 순서, 취소와 최종 판정을 관리한다.
 *
 * <p>Redis 분산 lock으로 동시에 하나의 활성 시험만 허용한다. Receiver를 먼저 준비한 뒤 Sender에
 * 입력 청크와 시작 명령을 전달하며, 양쪽 RoleResult가 모두 도착해야 최종 상태를 확정한다.
 */
package kr.co.lnis.server.session;
