// Receiver 화면은 조회만 수행한다. Sender의 수집 상태나 전송 동작에는 관여하지 않는다.
import {createPayloadViewer} from './dtn-payload.js?v=20260909';
const api = '/lnis/api/v1';
const $ = id => document.getElementById(id);
const payloadViewer = createPayloadViewer($('dtn-payload'));
let tests = [];
async function get(path) {
  const response = await fetch(api + path, {cache: 'no-store'});
  if (!response.ok) throw new Error('HTTP ' + response.status);
  return response.json();
}
function render() {
  const job = tests.find(item => item.testId === $('dtn-tests').value);
  payloadViewer.setJob(job);
  $('dtn-report').hidden = !job;
  if (!job) { $('dtn-result').textContent = '시험 대기'; return; }
  $('dtn-result').textContent = [
    '시험: ' + job.testId,
    'Sender: ' + job.senderAgentId + ' / Receiver: ' + job.receiverAgentId,
    '상태: ' + job.state,
    'DTN 수신: ' + (job.dtnReceived ? '완료' : '대기'),
    job.message || '',
    '판정: ' + (job.verdict || '대기'),
    '위치 비교 epoch: ' + (job.comparableEpochs ?? 0),
    '속도 비교 epoch: ' + (job.velocityComparableEpochs ?? 0)
  ].join('\n');
  $('dtn-report').href = api + '/dtn/tests/' + encodeURIComponent(job.testId) + '/report';
}
$('dtn-tests').onchange = render;
async function poll() {
  try {
    const [agents, recent] = await Promise.all([get('/agents'), get('/dtn/tests')]);
    $('dtn-connection').textContent = '서버 연결됨';
    const receivers = agents.filter(agent => agent.role === 'RECEIVER');
    $('dtn-agents').textContent = receivers.map(agent => agent.agentId + ' (' + agent.state + ')').join(' / ')
      || '등록된 Receiver Agent 없음';
    const selected = $('dtn-tests').value;
    tests = recent;
    $('dtn-tests').replaceChildren(...tests.map(job =>
      new Option(job.createdAt + ' / ' + job.receiverAgentId + ' / ' + job.state, job.testId)));
    if (tests.some(job => job.testId === selected)) $('dtn-tests').value = selected;
    render();
  } catch (error) {
    // 연결 장애 시 이전 결과가 최신 상태인 것처럼 보이지 않도록 표시한다.
    $('dtn-connection').textContent = '서버 조회 실패: ' + error.message + ' (기존 표시는 마지막 조회 결과)';
  } finally { setTimeout(poll, 2000); }
}
poll();
