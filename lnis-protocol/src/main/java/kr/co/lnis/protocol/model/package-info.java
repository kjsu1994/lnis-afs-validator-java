/**
 * REST 세션, WebSocket envelope, 시험 상태와 결과에 공통으로 사용되는 불변 모델을 정의한다.
 *
 * <p>Jackson으로 서버와 Agent 사이에서 직렬화되므로 DTO 필드 이름은 JSON 계약의 일부다. 이름 또는 enum 값을 변경할 때는 양쪽 배포본을 동시에 갱신하고
 * 저장된 Redis 데이터의 호환성도 확인해야 한다.
 */
package kr.co.lnis.protocol.model;
