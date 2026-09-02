/**
 * 업로드 또는 GNSS 수집으로 만들어진 canonical GRAW 입력의 수명주기를 관리한다.
 *
 * <p>1MiB 이하 순차 청크를 파일에 저장하고, 완료 시 전체 크기·record 구조·record 수·SHA-256을
 * 검증한다. 미완성 입력은 1시간, 완료 입력은 24시간 TTL을 사용한다.
 */
package kr.co.lnis.server.input;
