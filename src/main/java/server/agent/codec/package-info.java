/**
 * 기존 C AFS 코덱을 Java에서 호출하기 위한 JNA 경계 계층이다.
 *
 * <p>DLL ABI version, 입력 bit 배열 크기, TOI 범위를 검증하고 네이티브 오류를 Java 예외로 변환한다. 네이티브 함수의 thread safety를
 * 보장하기 위해 호출 구간을 단일 lock으로 보호한다.
 */
package server.agent.codec;
