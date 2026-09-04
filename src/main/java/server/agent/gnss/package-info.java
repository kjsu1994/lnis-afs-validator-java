/**
 * Windows COM 포트와 u-blox 수신기에서 원시 GNSS 데이터를 수집한다.
 *
 * <p>UBX checksum을 검증하고 RXM-RAWX/RXM-SFRBX를 canonical GRAW로 변환한다. 수집을 위해 변경하는 u-blox 출력률은 RAM에만
 * 적용하며 정상 종료 시 기존 설정을 복원한다.
 */
package server.agent.gnss;
