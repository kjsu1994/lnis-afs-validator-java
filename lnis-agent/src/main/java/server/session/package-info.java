/**
 * Sender/Receiver 시험 실행에 필요한 AFS frame 처리와 UDP 전송 기능을 묶는다.
 *
 * <p>afs 하위 패키지는 GRAW fragment 생성·복원과 오류 주입을, transport 하위 패키지는 실제 UDP
 * 송수신·중복 제거·무결성 판정·결과 반환을 담당한다.
 */
package server.session;
