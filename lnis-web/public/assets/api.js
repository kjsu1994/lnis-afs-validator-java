const API = '/lnis/api/v1';

const METRIC_NAMES = {
    DecodedFrames: '복호화 프레임',
    Sb2CrcValidFrames: 'SB2 CRC 정상 프레임',
    Sb3CrcValidFrames: 'SB3 CRC 정상 프레임',
    Sb4CrcValidFrames: 'SB4 CRC 정상 프레임',
    CorrectedSymbols: '오류 정정 심볼',
    RecoveredSyncFrames: '동기 복구 프레임',
};

/** LNIS REST API를 호출하고 서버 오류 본문을 사용자가 읽을 수 있는 예외로 변환한다. */
export async function request(path, options = {}) {
    const binaryBody = options.body instanceof Uint8Array;
    const response = await fetch(API + path, {
        headers: {
            'Content-Type': binaryBody ? 'application/octet-stream' : 'application/json',
            ...(options.headers || {}),
        },
        ...options,
    });

    if (!response.ok) {
        let detail;
        try {
            detail = await response.json();
        } catch {
            detail = { detail: await response.text() };
        }
        throw new Error(detail.detail || detail.message || `HTTP ${response.status}`);
    }

    return response.status === 204 ? null : response.json();
}

export const agents = () => request('/agents');

/** 상태 WebSocket을 열고 연결이 끊기면 지수 백오프로 자동 재연결한다. */
export function statusSocket(onEvent, onState) {
    let attempt = 0;
    let socket;
    let closed = false;

    const connect = () => {
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        socket = new WebSocket(`${scheme}://${location.host}/lnis/ws/status`);
        socket.onopen = () => {
            attempt = 0;
            onState(true);
        };
        socket.onmessage = (event) => {
            try {
                onEvent(JSON.parse(event.data));
            } catch {
                // 잘못된 단일 메시지가 이후 정상 상태 이벤트 처리를 중단시키지 않게 무시한다.
            }
        };
        socket.onclose = () => {
            onState(false);
            if (!closed) {
                const retryDelay = Math.min(30000, 1000 * (2 ** attempt++));
                setTimeout(connect, retryDelay);
            }
        };
        socket.onerror = () => socket.close();
    };

    connect();
    return () => {
        closed = true;
        socket?.close();
    };
}

/** 시간 접두어를 붙여 이벤트 로그를 추가하고 항상 최신 행으로 스크롤한다. */
export function log(target, message) {
    const time = new Date().toLocaleTimeString('ko-KR', { hour12: false });
    target.textContent += `[${time}] ${message}\n`;
    target.scrollTop = target.scrollHeight;
}

export function setPill(element, text, state = '') {
    element.textContent = text;
    element.className = `pill ${state}`;
}

/** Redis 결과를 요청 시점에 JSON/CSV 실제 파일로 내려받는 링크를 만든다. */
export function downloads(container, sessionId, role) {
    container.classList.remove('hidden');
    container.replaceChildren();

    for (const name of [
        'result.json',
        'metrics-summary.csv',
        'metrics-timeseries.csv',
    ]) {
        const link = document.createElement('a');
        link.textContent = `${role.toUpperCase()} ${name}`;
        link.href = `${API}/sessions/${sessionId}/artifacts/${role}/${name}`;
        container.append(link);
    }
}

function displayNumber(value) {
    return Number(value).toLocaleString('ko-KR', {
        maximumFractionDigits: 3,
    });
}

function addMetric(items, name, value, unit, description) {
    if (value === undefined || value === null) {
        return;
    }
    items.push({ name, value, unit, description });
}

/** RoleResult를 복호화·네트워크·무결성 항목별 화면 카드로 확장한다. */
function resultMetrics(result) {
    const items = (result?.metrics || []).map((metric) => ({
        name: METRIC_NAMES[metric.name] || metric.name,
        value: metric.value ?? metric.status,
        unit: metric.unit || '',
        description: metric.description || metric.detail || `${metric.name} 측정 결과`,
    }));
    const counters = result?.counters || {};
    const integrity = result?.integrity || {};

    addMetric(items, '예상 프레임', counters.expectedLogicalFrames, 'frame',
        'Sender가 전송하도록 준비한 전체 논리 프레임 수입니다.');
    addMetric(items, '수신 프레임', counters.receivedLogicalFrames, 'frame',
        'Receiver가 중복을 제거하고 정상 수신한 논리 프레임 수입니다.');
    addMetric(items, '송신 데이터그램', counters.sentDatagrams, 'datagram',
        '반복 송신 중 실제 네트워크로 전송된 UDP 데이터그램 수입니다.');
    addMetric(items, '수신 데이터그램', counters.receivedDatagrams, 'datagram',
        'Receiver가 소켓에서 수신한 UDP 데이터그램 수입니다.');
    addMetric(items, '중복 데이터그램', counters.duplicateDatagrams, 'datagram',
        '반복 송신으로 수신되었지만 같은 프레임이라 제외한 데이터그램 수입니다.');
    addMetric(items, '손상 데이터그램', counters.corruptDatagrams, 'datagram',
        '패킷 구조 또는 CRC 검증에 실패한 데이터그램 수입니다.');
    addMetric(items, '의도적 Drop', counters.simulatedDroppedDatagrams, 'datagram',
        'Test E 설정에 따라 Sender가 의도적으로 보내지 않은 데이터그램 수입니다.');
    addMetric(items, '처리 데이터', counters.rawBytes, 'byte',
        '시험에서 송신하거나 Receiver가 복원한 GRAW 데이터 크기입니다.');
    addMetric(items, '원본 크기', integrity.sourceLength, 'byte',
        'Sender 원본 GRAW의 전체 크기입니다.');
    addMetric(items, '복원 크기', integrity.reconstructedLength, 'byte',
        'Receiver가 AFS 프레임에서 다시 조립한 GRAW 크기입니다.');
    addMetric(items, '원본 레코드', integrity.expectedRecords, 'record',
        'Sender 원본 GRAW에 포함된 레코드 수입니다.');
    addMetric(items, '복원 레코드', integrity.reconstructedRecords, 'record',
        'Receiver가 완전하게 재조립한 GRAW 레코드 수입니다.');
    if (integrity.sourceSha256 && integrity.reconstructedSha256) {
        addMetric(
            items,
            'SHA-256 비교',
            integrity.sourceSha256 === integrity.reconstructedSha256 ? '일치' : '불일치',
            '',
            '원본과 복원 GRAW 전체 바이트의 SHA-256 해시 비교 결과입니다.',
        );
    }
    return items;
}

/** 최종 결과의 모든 측정값을 카드로 표시하며 각 카드에 상세 도움말을 연결한다. */
export function renderMetrics(target, result) {
    target.replaceChildren();

    for (const metric of resultMetrics(result)) {
        const box = document.createElement('div');
        const name = document.createElement('span');
        const value = document.createElement('strong');
        const description = metric.description || `${metric.name} 측정 결과입니다.`;

        box.className = 'metric';
        box.dataset.tooltip = description;
        box.title = description;
        box.tabIndex = 0;
        name.textContent = metric.name;
        value.textContent = typeof metric.value === 'number'
            ? `${displayNumber(metric.value)}${metric.unit ? ` ${metric.unit}` : ''}`
            : `${metric.value}${metric.unit ? ` ${metric.unit}` : ''}`;
        box.append(name, value);
        target.append(box);
    }
}
