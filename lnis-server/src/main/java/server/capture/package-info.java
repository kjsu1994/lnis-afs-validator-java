/**
 * Sender Agent의 COM/GNSS 수집을 중앙 API에서 시작하고 종료하는 기능이다.
 *
 * <p>capture 자체가 COM 포트를 열지 않으며 Agent에 명령을 전달한다. Agent가 전송한 canonical 청크는
 * input 기능에 저장하고, 수집 종료 후 일반 GRAW 업로드와 같은 검증 절차로 확정한다.
 */
package server.capture;
