/**
 * H2에 저장된 역할별 시험 결과를 사용자가 보관할 파일로 변환한다.
 *
 * <p>웹에는 Sender/Receiver와 프레임 증거를 합친 JSON과 네 시트 Excel을 제공한다. 기존 역할별 JSON/CSV도 호환용으로 요청 시점에 생성하며 서버
 * 디스크에 결과 파일을 영구 저장하지 않는다.
 */
package server.central.artifact;
