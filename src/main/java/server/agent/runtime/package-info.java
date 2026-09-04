/**
 * 중앙 서버 명령을 Agent의 GNSS 수집과 Sender/Receiver 시험 작업으로 분배한다.
 *
 * <p>동시에 수행 중인 작업의 상태를 READY/BUSY/ERROR로 관리하고, 입력 청크를 세션별로 조립하며, 처리 결과를 protocol envelope로 다시 중앙
 * 서버에 전달하는 orchestration 계층이다.
 */
package server.agent.runtime;
