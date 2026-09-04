/**
 * 중앙 Spring Boot 서버와 Windows Agent가 함께 사용하는 계약 전용 모듈이다.
 *
 * <p>이 패키지는 실행 환경이나 프레임워크에 의존하지 않는다. 서버와 Agent가 같은 메시지 구조와 binary 규격을 사용하도록 보장하며, 한쪽에서 계약을 변경하면 양쪽
 * 컴파일과 테스트가 함께 실패하도록 만들어 protocol 불일치를 조기에 발견한다.
 */
package server.protocol;
