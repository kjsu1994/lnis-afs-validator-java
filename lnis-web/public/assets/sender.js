import {
    request,
    agents,
    statusSocket,
    log,
    setPill,
    downloads,
    renderMetrics,
} from './api.js';

const $ = (id) => document.getElementById(id);
const eventLog = $('event-log');

let inputId = null;
let captureId = null;
let sessionId = null;
let captureRunning = false;
let sessionRunning = false;
let agentCache = [];

/**
 * 지정한 선택 상자의 Agent ID를 반환한다.
 *
 * Agent가 연결되지 않은 상태에서는 빈 경로 변수가 URL에 들어가지 않도록
 * API 호출 전에 사용자에게 원인을 설명하는 오류를 발생시킨다.
 */
function requireSelection(selectId, displayName) {
    const value = $(selectId).value.trim();
    if (!value) {
        throw new Error(
            `연결된 ${displayName} Agent가 없습니다. `
            + '해당 PC에서 LNIS Windows Agent를 먼저 실행하세요.',
        );
    }
    return value;
}

/** 선택 가능한 Agent가 없다는 상태를 select 안에서도 명확하게 표시한다. */
function addEmptyOption(select, roleName) {
    const option = new Option(`연결된 ${roleName} Agent 없음`, '');
    option.disabled = true;
    option.selected = true;
    select.add(option);
}

/** 팝업을 사용하지 않고 현재 작업 문맥을 유지한 채 결과를 안내한다. */
function showNotice(level, title, message) {
    const notice = $('page-notice');
    notice.className = `notice ${level}`;
    $('notice-title').textContent = title;
    $('notice-message').textContent = message;
}

function hideNotice() {
    $('page-notice').className = 'notice hidden';
}

/** 서버 내부 표현을 운영자가 바로 조치할 수 있는 안내 문장으로 바꾼다. */
function friendlyError(error) {
    const message = error?.message || '알 수 없는 오류가 발생했습니다.';
    if (/unknown agent|agent not found/i.test(message)) {
        return '선택한 Agent가 서버에 등록되어 있지 않습니다. Agent 실행 및 연결 상태를 확인하세요.';
    }
    if (/not connected|no active|websocket/i.test(message)) {
        return 'Agent WebSocket 연결이 끊어졌습니다. Agent를 다시 실행한 뒤 재시도하세요.';
    }
    if (/portname|must not be blank/i.test(message)) {
        return '수집에 사용할 COM 포트를 선택하세요.';
    }
    return message;
}

function reportError(context, error) {
    const message = friendlyError(error);
    showNotice('error', `${context} 실패`, message);
    log(eventLog, `${context} 실패: ${message}`);
}

$('notice-close').onclick = hideNotice;

/**
 * Agent 및 시험 실행 상태에 따라 잘못된 요청을 만들 수 있는 버튼을 잠근다.
 * 포트 새로고침은 Sender Agent가 연결돼야 하고, 수집 시작은 COM 포트까지
 * 선택돼야 한다. 시험 시작은 입력과 양쪽 Agent가 모두 준비돼야 한다.
 */
function updateControls() {
    const captureAgentReady = Boolean($('capture-agent').value);
    const comPortReady = Boolean($('com-port').value);
    const senderReady = Boolean($('sender-agent').value);
    const receiverReady = Boolean($('receiver-agent').value);

    $('refresh-ports').disabled = !captureAgentReady || captureRunning;
    $('capture-start').disabled = !captureAgentReady || !comPortReady || captureRunning;
    $('capture-stop').disabled = !captureRunning;
    $('test-start').disabled = !inputId || !senderReady || !receiverReady || sessionRunning;
    $('test-cancel').disabled = !sessionRunning;
}

/** 서버가 보관한 Agent 상태를 다시 읽고 역할별 선택 목록을 갱신한다. */
async function refreshAgents() {
    agentCache = await agents();

    for (const [id, role, roleName] of [
        ['sender-agent', 'SENDER', 'Sender'],
        ['capture-agent', 'SENDER', 'Sender'],
        ['receiver-agent', 'RECEIVER', 'Receiver'],
    ]) {
        const select = $(id);
        const previous = select.value;
        const matchingAgents = agentCache.filter((agent) => agent.role === role);

        select.replaceChildren();
        for (const agent of matchingAgents) {
            select.add(new Option(`${agent.agentId} · ${agent.state}`, agent.agentId));
        }

        if (matchingAgents.length === 0) {
            addEmptyOption(select, roleName);
        } else if ([...select.options].some((option) => option.value === previous)) {
            select.value = previous;
        }
    }

    paintAgents();
    updateControls();
}

/** 상단 상태 표시줄에 역할별 Agent의 현재 연결 상태를 표시한다. */
function paintAgents() {
    const sender = agentCache.find((agent) => agent.role === 'SENDER');
    const receiver = agentCache.find((agent) => agent.role === 'RECEIVER');

    setPill(
        $('sender-status'),
        sender ? `Sender ${sender.state}` : 'Sender OFFLINE',
        sender?.state === 'READY' ? 'online' : '',
    );
    setPill(
        $('receiver-status'),
        receiver ? `Receiver ${receiver.state}` : 'Receiver OFFLINE',
        receiver?.state === 'READY' ? 'online' : '',
    );
}

for (const tab of document.querySelectorAll('.tab')) {
    tab.onclick = () => {
        document.querySelectorAll('.tab').forEach((item) => item.classList.remove('active'));
        tab.classList.add('active');
        $('upload-panel').classList.toggle('hidden', tab.dataset.tab !== 'upload');
        $('capture-panel').classList.toggle('hidden', tab.dataset.tab !== 'capture');
    };
}

$('upload-button').onclick = async () => {
    const file = $('graw-file').files[0];
    if (!file) {
        showNotice('warning', '파일 선택 필요', '업로드할 capture.graw 파일을 선택하세요.');
        return;
    }

    try {
        const input = await request('/inputs', {
            method: 'POST',
            body: JSON.stringify({
                fileName: file.name,
                size: file.size,
                kind: 'GRAW_UPLOAD',
            }),
        });
        inputId = input.inputId;

        const chunkSize = 1024 * 1024;
        for (let offset = 0, index = 0; offset < file.size; offset += chunkSize, index += 1) {
            const bytes = new Uint8Array(
                await file.slice(offset, offset + chunkSize).arrayBuffer(),
            );
            await request(`/inputs/${inputId}/chunks/${index}`, {
                method: 'PUT',
                body: bytes,
            });
            $('upload-progress').value = Math.round(
                (Math.min(file.size, offset + bytes.length) / file.size) * 100,
            );
        }

        const complete = await request(`/inputs/${inputId}/complete`, { method: 'POST' });
        $('input-summary').textContent = `${complete.recordCount.toLocaleString()} records · `
            + `${complete.receivedSize.toLocaleString()} bytes · SHA-256 ${complete.sha256}`;
        log(eventLog, 'GRAW 업로드 및 검증 완료');
        showNotice('success', '업로드 완료', 'GRAW 파일을 검증하고 Redis 입력 버퍼에 저장했습니다.');
        updateControls();
    } catch (error) {
        reportError('GRAW 업로드', error);
    }
};

$('capture-agent').onchange = () => {
    $('com-port').replaceChildren();
    $('capture-summary').textContent = 'COM 포트 새로고침을 눌러 조회하세요.';
    updateControls();
};

$('com-port').onchange = updateControls;

$('refresh-ports').onclick = async () => {
    try {
        const agentId = requireSelection('capture-agent', 'Sender');
        $('refresh-ports').disabled = true;
        await request(`/agents/${encodeURIComponent(agentId)}/serial-ports/refresh`, {
            method: 'POST',
        });
        $('capture-summary').textContent = 'Agent의 COM 포트 응답을 기다리는 중입니다.';
        log(eventLog, `COM 포트 조회 명령 전송: ${agentId}`);
        showNotice('info', 'COM 포트 조회 중', `${agentId} Agent의 응답을 기다리고 있습니다.`);
    } catch (error) {
        reportError('COM 포트 조회', error);
    } finally {
        updateControls();
    }
};

$('capture-start').onclick = async () => {
    try {
        const senderAgentId = requireSelection('capture-agent', 'Sender');
        const portName = $('com-port').value;
        if (!portName) {
            throw new Error('수집할 COM 포트를 먼저 선택하세요.');
        }

        const result = await request('/captures', {
            method: 'POST',
            body: JSON.stringify({
                senderAgentId,
                portName,
                baudRate: Number($('baud').value),
                protocolId: $('protocol').value,
                sessionName: 'web-capture',
                receiverModel: 'u-blox',
                firmwareVersion: 'auto',
                dtrEnabled: false,
                rtsEnabled: false,
            }),
        });
        captureId = result.inputId;
        inputId = result.inputId;
        captureRunning = true;
        $('capture-summary').textContent = `${portName} 수집 중`;
        log(eventLog, `GNSS 수집 시작: ${senderAgentId} / ${portName}`);
        showNotice('success', 'GNSS 수집 시작', `${senderAgentId}의 ${portName} 포트를 수집 중입니다.`);
        updateControls();
    } catch (error) {
        reportError('GNSS 수집 시작', error);
    }
};

$('capture-stop').onclick = async () => {
    try {
        if (!captureId) {
            throw new Error('진행 중인 GNSS 수집이 없습니다.');
        }
        const senderAgentId = requireSelection('capture-agent', 'Sender');
        await request(
            `/captures/${captureId}/stop?senderAgentId=${encodeURIComponent(senderAgentId)}`,
            { method: 'POST' },
        );
        await new Promise((resolve) => setTimeout(resolve, 500));

        const complete = await request(`/captures/${captureId}/complete`, { method: 'POST' });
        $('capture-summary').textContent = `수집 완료 · ${complete.recordCount} records · `
            + `${complete.receivedSize.toLocaleString()} bytes`;
        captureRunning = false;
        captureId = null;
        showNotice('success', 'GNSS 수집 완료', '수집 데이터를 검증하고 시험 입력으로 준비했습니다.');
        updateControls();
    } catch (error) {
        reportError('GNSS 수집 종료', error);
    }
};

/** 선택한 시험 종류에 필요한 조건 입력만 화면에 노출한다. */
function conditions() {
    const type = $('test-type').value;
    document.querySelectorAll('.conditional').forEach((item) => item.classList.add('hidden'));

    if (type === 'TEST_B_RANDOM_ERRORS' || type === 'TEST_C_BURST_ERRORS') {
        document.querySelectorAll('.conditional.error')
            .forEach((item) => item.classList.remove('hidden'));
    }
    if (type === 'TEST_D_SYNC_RECOVERY') {
        document.querySelectorAll('.conditional.error,.conditional.sync')
            .forEach((item) => item.classList.remove('hidden'));
    }
    if (type === 'TEST_E_UDP_DROP') {
        document.querySelectorAll('.conditional.drop')
            .forEach((item) => item.classList.remove('hidden'));
    }
}

$('test-type').onchange = conditions;
conditions();

$('test-start').onclick = async () => {
    if (!inputId) {
        showNotice('warning', '시험 입력 필요', 'GRAW 업로드 또는 GNSS COM 수집을 먼저 완료하세요.');
        return;
    }

    try {
        const senderAgentId = requireSelection('sender-agent', 'Sender');
        const receiverAgentId = requireSelection('receiver-agent', 'Receiver');
        const body = {
            senderAgentId,
            receiverAgentId,
            inputId,
            transport: {
                broadcastAddress: $('broadcast-address').value,
                dataPort: Number($('data-port').value),
                resultPort: Number($('result-port').value),
                repeatCount: Number($('repeat-count').value),
                resultTimeoutSeconds: Number($('result-timeout').value),
                endGraceMilliseconds: 1000,
                probeIntervalMilliseconds: 1000,
            },
            options: {
                testType: $('test-type').value,
                errorCount: Number($('error-count').value),
                errorSeed: Number($('error-seed').value),
                syncDamageInterval: Number($('sync-interval').value),
                dropRatePercent: Number($('drop-rate').value),
                dropSeed: Number($('drop-seed').value),
                thresholds: {},
            },
        };

        const session = await request('/sessions', {
            method: 'POST',
            body: JSON.stringify(body),
        });
        sessionId = session.sessionId;
        sessionRunning = true;
        log(eventLog, `시험 ${sessionId} 시작`);
        showNotice('success', '시험 시작', `시험 ${sessionId} 명령을 양쪽 Agent에 전송했습니다.`);
        updateControls();
    } catch (error) {
        reportError('시험 시작', error);
    }
};

$('test-cancel').onclick = async () => {
    if (!sessionId) {
        return;
    }
    await request(`/sessions/${sessionId}/cancel`, { method: 'POST' });
    sessionRunning = false;
    showNotice('info', '시험 취소', `시험 ${sessionId} 취소 명령을 전송했습니다.`);
    updateControls();
};

statusSocket(
    (event) => {
        if (event.type === 'AGENT_STATUS') {
            refreshAgents().catch((error) => log(eventLog, error.message));
        }

        if (event.payload?.ports) {
            const selectedAgentId = $('capture-agent').value;
            if (!event.agentId || event.agentId === selectedAgentId) {
                const select = $('com-port');
                const portOptions = event.payload.ports.map(
                    (port) => new Option(port.name, port.name),
                );
                select.replaceChildren(...portOptions);
                $('capture-summary').textContent = portOptions.length > 0
                    ? `${portOptions.length}개 COM 포트를 찾았습니다.`
                    : '사용 가능한 COM 포트가 없습니다.';
                updateControls();
            }
        }

        if (event.sessionId && sessionId && event.sessionId !== sessionId) {
            return;
        }

        const payload = event.payload || {};
        log(eventLog, `${event.type}: ${payload.message || payload.stage || ''}`);
        if (Number.isFinite(payload.percent)) {
            $('session-progress').value = payload.percent;
            $('progress-label').textContent = `${payload.percent}%`;
        }
        if (event.type === 'RESULT') {
            renderMetrics($('metrics'), payload);
            const verdict = payload.verdict?.toLowerCase();
            $('verdict').textContent = `판정: ${payload.verdict}`;
            $('verdict').className = `verdict ${verdict === 'pass' ? 'pass' : 'fail'}`;
            downloads(
                $('downloads'),
                event.sessionId,
                event.role === 'SENDER' ? 'tx' : 'rx',
            );
            sessionRunning = false;
            updateControls();
        }
    },
    (online) => setPill(
        $('server-status'),
        online ? '서버 연결됨' : '서버 재연결 중',
        online ? 'online' : 'warning',
    ),
);

updateControls();
refreshAgents().catch((error) => log(eventLog, error.message));
setInterval(() => refreshAgents().catch(() => {}), 5000);
