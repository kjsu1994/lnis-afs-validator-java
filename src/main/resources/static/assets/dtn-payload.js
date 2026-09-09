// JSON 본문은 HTML로 해석하지 않는다. 정렬 보기는 화면에만 적용하고 다운로드는 원문 API를 사용한다.
export function payloadUrl(testId, direction, download = false) {
  if (!['sent', 'received'].includes(direction)) throw new Error('지원하지 않는 JSON 방향입니다.');
  return '/lnis/api/v1/dtn/tests/' + encodeURIComponent(testId) + '/payload/' + direction +
    (download ? '?download=true' : '');
}

export function displayedJson(original, pretty) {
  return pretty ? JSON.stringify(JSON.parse(original), null, 2) : original;
}

export function createPayloadViewer(container) {
  const create = (tag, text) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const title = create('h2', '송수신 JSON 본문');
  const controls = create('div');
  controls.className = 'dtn-payload-controls';
  const sent = create('button', '송신 JSON 보기');
  const received = create('button', '수신 JSON 보기');
  sent.type = received.type = 'button';
  sent.disabled = received.disabled = true;
  controls.append(sent, received);
  const status = create('p', '시험을 선택하면 준비된 JSON을 확인할 수 있습니다.');
  status.setAttribute('role', 'status');
  const panel = create('div');
  panel.hidden = true;
  const caption = create('p');
  const prettyLabel = create('label');
  const pretty = create('input');
  pretty.type = 'checkbox';
  prettyLabel.append(pretty, document.createTextNode(' 정렬해서 보기 (원문은 변경하지 않음)'));
  const download = create('a', 'JSON 파일 다운로드');
  const close = create('button', '보기 닫기');
  close.type = 'button';
  const toolbar = create('div');
  toolbar.className = 'dtn-payload-controls';
  toolbar.append(prettyLabel, download, close);
  const text = create('textarea');
  text.readOnly = true;
  text.rows = 20;
  text.spellcheck = false;
  text.className = 'dtn-payload-text';
  text.setAttribute('aria-label', '선택한 시험의 JSON 본문');
  panel.append(caption, toolbar, text);
  container.append(title, controls, status, panel);
  let job = null, original = '', generation = 0, pending = null;

  function reset() {
    generation++;
    pending?.abort();
    pending = null;
    original = '';
    text.value = '';
    pretty.checked = false;
    panel.hidden = true;
    download.removeAttribute('href');
  }

  async function show(direction) {
    if (!job) return;
    reset();
    const selectedId = job.testId;
    const requestGeneration = generation;
    pending = new AbortController();
    status.textContent = 'JSON 본문을 불러오는 중입니다.';
    try {
      const response = await fetch(payloadUrl(selectedId, direction), {cache: 'no-store', signal: pending.signal});
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.detail || error.message || ('HTTP ' + response.status));
      }
      const body = await response.text();
      // 조회 중 시험을 바꾸거나 닫으면 이전 요청이 새 시험의 본문을 덮어쓰지 않는다.
      if (requestGeneration !== generation) return;
      original = body;
      text.value = original;
      caption.textContent = (direction === 'sent' ? '송신 요청 JSON' : '최초 접수 수신 JSON') + ' · 시험 ' + selectedId;
      download.href = payloadUrl(selectedId, direction, true);
      panel.hidden = false;
      const legacy = response.headers.get('X-LNIS-Payload-Representation') === 'legacy-normalized';
      status.textContent = legacy
        ? '과거 시험의 정규화된 저장본입니다. 당시 원문의 공백·줄바꿈은 보관되지 않았습니다.'
        : direction === 'sent'
          ? '외부 DTN/HDTN에 전달할 요청 본문입니다. 본문 준비 자체가 전송 성공을 뜻하지는 않습니다.'
          : '접수 당시 원문입니다. 정렬 보기는 표시만 바꾸며, 다운로드는 원문을 유지합니다.';
    } catch (error) {
      if (requestGeneration === generation && error.name !== 'AbortError') status.textContent = 'JSON 조회 실패: ' + error.message;
    }
  }

  sent.onclick = () => show('sent');
  received.onclick = () => show('received');
  pretty.onchange = () => {
    try { text.value = displayedJson(original, pretty.checked); }
    catch { pretty.checked = false; text.value = original; status.textContent = '정렬할 수 없어 원문을 표시합니다.'; }
  };
  close.onclick = () => { reset(); status.textContent = 'JSON 보기를 닫았습니다.'; };

  return {
    setJob(nextJob) {
      if (job?.testId !== nextJob?.testId) {
        reset();
        status.textContent = nextJob ? '준비된 송신 또는 수신 JSON을 선택하세요.' : '시험을 선택하세요.';
      }
      job = nextJob || null;
      sent.disabled = !job?.sentPayloadAvailable;
      received.disabled = !job?.receivedPayloadAvailable;
    }
  };
}
