// DTN 화면 전용 코드다. 기존 AFS 화면의 DOM/이벤트 코드는 공유하지 않는다.
import {createPayloadViewer} from './dtn-payload.js?v=20260909';
const api = '/lnis/api/v1';
const $ = id => document.getElementById(id);
const payloadViewer = createPayloadViewer($('dtn-payload'));
let captureId = sessionStorage.getItem('dtn.capture');
let captureSender = sessionStorage.getItem('dtn.captureSender');
let inputId = sessionStorage.getItem('dtn.input');
let testId = sessionStorage.getItem('dtn.test');
let stopping = false, busy = false, configured = false, timer, stopResolve, stopReject;
async function request(path, options = {}) {
  const response = await fetch(api + path, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.detail || body.message || ('HTTP ' + response.status));
  return body;
}
function post(path, body) {
  return request(path, {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body)});
}
function message(text) { $('dtn-message').textContent = text; }
function buttons() {
  $('dtn-start').disabled = busy || !!captureId || !socket || socket.readyState !== WebSocket.OPEN;
  $('dtn-stop').disabled = busy || !captureId || stopping;
  $('dtn-send').disabled = busy || !!captureId || !inputId || !configured || !$('dtn-send-url').value.trim();
}
async function action(work) {
  busy = true; buttons();
  try { await work(); } catch (error) { message(error.message); }
  finally { busy = false; buttons(); }
}
function options(id, agents, role) {
  const select = $(id), old = select.value;
  select.replaceChildren(...agents.filter(a => a.role === role).map(a =>
    new Option(a.agentId + ' (' + a.state + ')', a.agentId)));
  if ([...select.options].some(o => o.value === old)) select.value = old;
}
async function agents() {
  const list = await request('/agents');
  options('dtn-sender', list, 'SENDER'); options('dtn-receiver', list, 'RECEIVER');
}
let socket;
function connect() {
  socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/lnis/ws/status');
  socket.onopen = buttons;
  socket.onclose = () => {
    if (stopReject) stopReject(new Error('수집 종료 확인 연결이 끊겼습니다. 다시 종료를 눌러주세요.'));
    buttons(); setTimeout(connect, 2000);
  };
  socket.onmessage = event => {
    const data = JSON.parse(event.data);
    if (data.agentId === $('dtn-sender').value && data.payload?.ports) {
      $('dtn-port').replaceChildren(...data.payload.ports.map(p => new Option(p.name, p.name)));
    }
    if (data.sessionId !== captureId) return;
    if (data.type === 'ERROR') {
      message(data.payload?.message || 'Agent 수집 오류');
      if (stopReject) stopReject(new Error('수집 오류가 발생했습니다.'));
    }
    if (data.type === 'GNSS_STATUS') {
      message(data.payload.message || data.payload.stage);
      if (data.payload.stage === 'Stopped' && stopResolve) stopResolve();
    }
  };
}
async function stop() {
  if (!captureId || stopping) return;
  stopping = true; buttons(); clearTimeout(timer);
  try {
    if (socket.readyState !== WebSocket.OPEN) throw new Error('서버 상태 연결을 기다려주세요.');
    // 마지막 수집 청크가 서버에 도착한 Stopped 이벤트 이후에만 입력을 확정한다.
    let timeout;
    const stopped = new Promise((resolve, reject) => {
      stopResolve = resolve; stopReject = reject;
      timeout = setTimeout(() => reject(new Error('수집 종료 확인 시간 초과. 다시 종료를 눌러주세요.')), 15000);
    });
    const command = post('/dtn/captures/' + captureId + '/stop?senderAgentId=' + encodeURIComponent(captureSender));
    try { await Promise.all([stopped, command]); }
    finally { clearTimeout(timeout); stopResolve = stopReject = null; }
    const input = await post('/captures/' + captureId + '/complete');
    inputId = input.inputId; sessionStorage.setItem('dtn.input', inputId);
    captureId = null; sessionStorage.removeItem('dtn.capture'); sessionStorage.removeItem('dtn.captureSender');
    message('수집 완료: ' + input.recordCount + ' 레코드 / ' + input.receivedSize + ' bytes. 전송 버튼으로 시험하세요.');
  } finally { stopping = false; buttons(); }
}
$('dtn-refresh').onclick = () => action(async () => {
  await agents();
  if (!$('dtn-sender').value) throw new Error('Sender Agent가 없습니다.');
  await post('/agents/' + encodeURIComponent($('dtn-sender').value) + '/serial-ports/refresh');
});
$('dtn-start').onclick = () => action(async () => {
  const seconds = Number($('dtn-seconds').value);
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 300) throw new Error('수집 시간은 1~300초입니다.');
  if (!$('dtn-sender').value || !$('dtn-port').value) throw new Error('Sender와 COM 포트를 선택하세요.');
  captureSender = $('dtn-sender').value;
  const input = await post('/captures', {senderAgentId: captureSender, portName: $('dtn-port').value,
    baudRate: Number($('dtn-baud').value), protocolId: 'ubx', receiverModel: 'EVK-F9T',
    firmwareVersion: 'auto', sessionName: 'DTN capture', dtrEnabled: false, rtsEnabled: false});
  captureId = input.inputId; inputId = null;
  sessionStorage.setItem('dtn.capture', captureId); sessionStorage.setItem('dtn.captureSender', captureSender);
  sessionStorage.removeItem('dtn.input');
  timer = setTimeout(() => action(stop), seconds*1000);
  message('COM 수집 중. ' + seconds + '초 후 자동 종료합니다. 이 화면을 유지해주세요.');
});
$('dtn-stop').onclick = () => action(stop);
$('dtn-send-url').oninput = buttons;
$('dtn-send').onclick = () => action(async () => {
  if (!$('dtn-send-url').reportValidity()) throw new Error('DTN/HDTN 어댑터의 전체 REST URL을 입력하세요.');
  const sendUrl = $('dtn-send-url').value.trim();
  const result = await post('/dtn/tests', {inputId, senderAgentId: $('dtn-sender').value,
    receiverAgentId: $('dtn-receiver').value, sendUrl});
  testId = result.testId; sessionStorage.setItem('dtn.test', testId);
  payloadViewer.setJob(result);
  $('dtn-result').textContent = result.message;
  // 동일 수집 재전송 UI는 이번 범위에 포함하지 않는다.
  inputId = null; sessionStorage.removeItem('dtn.input');
});
async function poll() {
  try {
    if (testId) {
      const result = await request('/dtn/tests/' + testId);
      payloadViewer.setJob(result);
      $('dtn-result').textContent = result.state + '\n' + result.message +
        (result.sendUrl ? '\nPOST URL: ' + result.sendUrl : '') +
        '\nDTN 수신: ' + (result.dtnReceived ? '완료' : '대기') +
        (result.verdict ? '\n판정: ' + result.verdict + '\n위치 비교 epoch: ' + result.comparableEpochs +
          '\n속도 비교 epoch: ' + result.velocityComparableEpochs : '');
      $('dtn-report').href = api + '/dtn/tests/' + testId + '/report';
      $('dtn-report').hidden = false;
    }
  } catch (error) { message(error.message); }
  setTimeout(poll, 2000);
}
connect();
action(async () => {
  const config = await request('/dtn/config'); configured = config.sendReady ?? config.receiveConfigured ?? config.configured;
  $('dtn-send-url').value = config.defaultSendUrl || '';
  $('dtn-config').textContent = config.nodeRole === 'SENDER'
    ? '독립 송신 노드 · callback은 수신 PC로 전달됩니다. 전송할 DTN/HDTN 어댑터 URL을 확인하세요.'
    : configured ? '수신 인증 설정 완료 · 전송할 DTN/HDTN 어댑터 URL을 확인하세요.' :
    '수신 인증 설정 필요: LNIS_DTN_RECEIVE_TOKEN';
  await agents();
  if (captureId) message('진행 중이던 수집이 있습니다. 수집 종료 버튼으로 확정하세요.');
}).then(poll);
