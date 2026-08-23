import { request } from './api.js?v=20260823-frame4';

const FRAME_BITS = 6000;
const COLUMNS = 100;
const ROWS = 60;
const CELL_SIZE = 5;

/** Base64로 받은 750 byte AFS 프레임을 브라우저 byte 배열로 변환한다. */
function decodeFrame(value) {
    if (!value) return null;
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function bitAt(bytes, position) {
    if (!bytes || position < 0 || position >= FRAME_BITS) return null;
    return (bytes[position >> 3] >> (7 - (position & 7))) & 1;
}

function number(value) {
    return value === null || value === undefined
        ? '비교 자료 없음'
        : `${Number(value).toLocaleString('ko-KR')} bit`;
}

function shortHash(value) {
    return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '없음';
}

/** 한 단계의 6,000개 비트를 100×60 점자형 지도에 그린다. */
function drawMap(canvas, bytes, differences, selectedPosition = null) {
    const context = canvas.getContext('2d');
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#edf2f4';
    context.fillRect(0, 0, canvas.width, canvas.height);
    if (!bytes) {
        context.fillStyle = '#607780';
        context.font = 'bold 16px sans-serif';
        context.fillText('이 단계의 프레임 자료가 없습니다.', 24, 42);
        return;
    }
    for (let position = 0; position < FRAME_BITS; position += 1) {
        const column = position % COLUMNS;
        const row = Math.floor(position / COLUMNS);
        context.fillStyle = differences.has(position)
            ? '#e05252'
            : bitAt(bytes, position) === 1 ? '#183d4c' : '#dbe5e8';
        context.fillRect(
            column * CELL_SIZE,
            row * CELL_SIZE,
            CELL_SIZE - 1,
            CELL_SIZE - 1,
        );
    }
    if (selectedPosition !== null) {
        const column = selectedPosition % COLUMNS;
        const row = Math.floor(selectedPosition / COLUMNS);
        context.strokeStyle = '#f4b400';
        context.lineWidth = 2;
        context.strokeRect(
            column * CELL_SIZE,
            row * CELL_SIZE,
            CELL_SIZE,
            CELL_SIZE,
        );
    }
}

function stageCard(stage) {
    const card = document.createElement('article');
    const heading = document.createElement('div');
    const title = document.createElement('strong');
    const badge = document.createElement('span');
    const description = document.createElement('p');
    const viewport = document.createElement('div');
    const canvas = document.createElement('canvas');
    const hover = document.createElement('p');

    card.className = 'frame-map-card';
    title.textContent = stage.title;
    badge.className = `frame-map-badge ${stage.differences.size === 0 ? 'same' : 'different'}`;
    badge.textContent = stage.bytes
        ? (stage.differences.size === 0
            ? '비교 기준과 동일'
            : `${stage.differences.size.toLocaleString('ko-KR')} bit 차이`)
        : '자료 없음';
    description.textContent = stage.description;
    canvas.width = COLUMNS * CELL_SIZE;
    canvas.height = ROWS * CELL_SIZE;
    canvas.setAttribute('role', 'img');
    canvas.setAttribute(
        'aria-label',
        `${stage.title}, 100열 60행 AFS 6,000비트 지도, ${badge.textContent}`,
    );
    canvas.tabIndex = 0;
    hover.className = 'frame-hover-info';
    hover.textContent = `SHA-256 ${shortHash(stage.hash)} · ${stage.comparison}`;
    heading.className = 'frame-map-heading';
    heading.append(title, badge);
    viewport.className = 'frame-map-viewport';
    viewport.append(canvas);
    card.append(heading, description, viewport, hover);
    return { card, canvas, hover, ...stage };
}

function comparisonCards(summary) {
    const grid = document.createElement('div');
    grid.className = 'frame-comparison-summary';
    const values = [
        ['오류 주입 결과', number(summary.referenceToTransmittedDifferences),
            '기준 프레임과 실제 송신 프레임의 차이입니다. Test B/C/D에서는 설정한 오류가 이 단계에 나타납니다.'],
        ['전송 중 추가 변화', number(summary.transmittedToReceivedDifferences),
            '실제 송신 프레임과 Receiver가 채택한 프레임의 차이입니다. 0이면 UDP로 받은 AFS 원문이 같습니다.'],
        ['최종 복구 차이', number(summary.referenceToReencodedDifferences),
            '기준 프레임과 복호화 후 재인코딩 검증 프레임의 차이입니다. 0이면 6,000비트 기준으로 복구됐습니다.'],
    ];
    for (const [title, value, description] of values) {
        const item = document.createElement('article');
        const name = document.createElement('span');
        const result = document.createElement('strong');
        const help = document.createElement('p');
        name.textContent = title;
        result.textContent = value;
        help.textContent = description;
        item.title = description;
        item.append(name, result, help);
        grid.append(item);
    }
    return grid;
}

async function renderSelectedFrame(container, sessionId, frameIndex) {
    const detail = await request(`/sessions/${sessionId}/frame-evidence/${frameIndex}`);
    const summary = detail.summary;
    const stages = [
        {
            title: '1. 기준 AFSFrame',
            description: 'GRAW를 정상 인코딩한 비교 기준입니다. 아직 시험 오류를 넣지 않은 6,000비트입니다.',
            bytes: decodeFrame(detail.referenceFrame),
            hash: summary.referenceSha256,
            differences: new Set(),
            comparison: '이 지도가 이후 단계의 기준입니다.',
        },
        {
            title: '2. 실제 송신 AFSFrame',
            description: '시험 오류를 주입한 뒤 Sender가 UDP에 실어 보낸 실제 6,000비트입니다. 빨강은 기준과 달라진 위치입니다.',
            bytes: decodeFrame(detail.transmittedFrame),
            hash: summary.transmittedSha256,
            differences: new Set(detail.referenceToTransmittedPositions || []),
            comparison: `기준 대비 ${number(summary.referenceToTransmittedDifferences)}`,
        },
        {
            title: '3. Receiver 수신 AFSFrame',
            description: 'UDP 구조와 CRC 검사를 통과해 Receiver가 채택한 실제 6,000비트입니다. 빨강은 송신 프레임과 달라진 위치입니다.',
            bytes: decodeFrame(detail.receivedFrame),
            hash: summary.receivedSha256,
            differences: new Set(detail.transmittedToReceivedPositions || []),
            comparison: `송신 대비 ${number(summary.transmittedToReceivedDifferences)}`,
        },
        {
            title: '4. 복호화 후 재인코딩 검증',
            description: 'Receiver가 복호화한 SB2/SB3/SB4를 같은 TOI로 다시 인코딩한 검증 프레임입니다. 빨강은 기준과 아직 다른 위치입니다.',
            bytes: decodeFrame(detail.reencodedFrame),
            hash: summary.reencodedSha256,
            differences: new Set(detail.referenceToReencodedPositions || []),
            comparison: `기준 대비 ${number(summary.referenceToReencodedDifferences)}`,
        },
    ];

    const content = container.querySelector('.frame-evidence-content');
    content.replaceChildren();
    const interpretation = document.createElement('div');
    interpretation.className = `frame-interpretation ${summary.referenceToReencodedDifferences === 0 ? 'pass' : 'warning'}`;
    const label = document.createElement('strong');
    const interpretationText = document.createElement('p');
    label.textContent = '이 프레임의 해석';
    interpretationText.textContent = summary.interpretation;
    interpretation.append(label, interpretationText);
    content.append(interpretation, comparisonCards(summary));

    const maps = document.createElement('div');
    maps.className = 'frame-map-grid';
    const rendered = stages.map(stageCard);
    rendered.forEach((item) => maps.append(item.card));
    content.append(maps);

    const redraw = (selectedPosition = null) => {
        rendered.forEach((item) => drawMap(
            item.canvas,
            item.bytes,
            item.differences,
            selectedPosition,
        ));
    };
    redraw();

    rendered.forEach((item) => {
        item.canvas.addEventListener('mousemove', (event) => {
            const bounds = item.canvas.getBoundingClientRect();
            const column = Math.floor((event.clientX - bounds.left) * COLUMNS / bounds.width);
            const row = Math.floor((event.clientY - bounds.top) * ROWS / bounds.height);
            const position = Math.min(FRAME_BITS - 1, row * COLUMNS + column);
            redraw(position);
            const values = stages.map((stage, index) => `${index + 1}단계=${bitAt(stage.bytes, position) ?? '-'}`);
            const message = `심볼 ${position.toLocaleString('ko-KR')} · ${values.join(' · ')}`;
            rendered.forEach((target) => {
                target.canvas.title = message;
                target.hover.textContent = message;
            });
        });
        item.canvas.addEventListener('mouseleave', () => {
            redraw();
            rendered.forEach((target) => {
                target.hover.textContent = `SHA-256 ${shortHash(target.hash)} · ${target.comparison}`;
            });
        });
    });
}

/** 세션 결과 아래에 프레임 선택기, 비교 요약, 네 장의 6,000비트 지도를 표시한다. */
export async function renderFrameEvidence(container, sessionId) {
    container.classList.remove('hidden');
    container.innerHTML = `
        <div class="frame-evidence-heading">
            <div>
                <span class="eyebrow">AFS FRAME EVIDENCE</span>
                <h3>6,000비트 프레임 직접 비교</h3>
                <p>각 점은 AFS 심볼 1개입니다. 빨간 점은 해당 단계의 비교 기준과 다른 심볼입니다.</p>
            </div>
            <label title="보관된 AFS 프레임 중 점자형 지도로 비교할 프레임을 선택합니다.">확인할 프레임 <select class="frame-selector" aria-label="확인할 AFS 프레임" title="확인할 AFS 프레임을 선택합니다."></select></label>
        </div>
        <p class="frame-retention-note">최대 500프레임의 상세 원문을 Redis에 24시간 보관합니다. 500프레임을 넘으면 처음 250개와 마지막 250개를 보관합니다.</p>
        <div class="frame-evidence-content" aria-live="polite">프레임 증거를 불러오는 중입니다.</div>`;
    try {
        let summaries = [];
        // RESULT와 별도 WebSocket 메시지인 프레임 증거 저장이 수백 ms 늦을 수 있어 짧게 재조회한다.
        for (let attempt = 0; attempt < 20; attempt += 1) {
            summaries = await request(`/sessions/${sessionId}/frame-evidence`);
            const rolesMerged = summaries.length > 0
                && summaries.every((item) => item.senderEvidenceAvailable
                    && item.receiverEvidenceAvailable);
            if (rolesMerged) break;
            await new Promise((resolve) => setTimeout(resolve, 250));
        }
        if (summaries.length === 0) {
            container.querySelector('.frame-evidence-content').textContent =
                '아직 보관된 프레임 증거가 없습니다. 최신 Agent가 실행 중인지 확인하세요.';
            return;
        }
        const selector = container.querySelector('.frame-selector');
        summaries.forEach((summary) => {
            const state = summary.referenceToReencodedDifferences === 0
                ? '복구 일치'
                : summary.intentionalSyncRejection ? '의도적 동기 제외' : '확인 필요';
            selector.add(new Option(
                `${summary.frameIndex + 1}번 (index ${summary.frameIndex}) · ${state}`,
                summary.frameIndex,
            ));
        });
        selector.onchange = () => renderSelectedFrame(
            container,
            sessionId,
            Number(selector.value),
        );
        await renderSelectedFrame(container, sessionId, Number(selector.value));
    } catch (error) {
        container.querySelector('.frame-evidence-content').textContent =
            `프레임 증거를 불러오지 못했습니다: ${error.message}`;
    }
}
