/**
 * Redis에 저장된 역할별 시험 결과를 사용자가 보관할 파일로 변환한다.
 *
 * <p>result.json, metrics-summary.csv, metrics-timeseries.csv를 요청 시점에 생성하며 서버 디스크에
 * 결과 파일을 영구 저장하지 않는다. CSV에는 Excel 호환을 위해 UTF-8 BOM을 붙인다.
 */
package kr.co.lnis.server.artifact;
