import assert from 'node:assert/strict';
import { formatEventLog } from '../public/assets/event-log.js';

const sessionId = 'event-log-regression-test';

const prepared = formatEventLog({
    type: 'TX_STATUS',
    sessionId,
    payload: {
        percent: 30,
        stage: 'Prepared',
        counters: {
            testType: 'TEST_B_RANDOM_ERRORS',
            sourceBytes: 4096,
            recordCount: 8,
            totalFrames: 4,
            destinationAddress: '127.0.0.1',
            dataPort: 45821,
            resultPort: 45822,
            repeatCount: 3,
            errorCount: 2,
            errorSeed: 7,
            injectedFrameCount: 4,
        },
    },
});
assert.match(prepared, /Test B 임의 비트 오류/);
assert.match(prepared, /GRAW 8 records, AFS 4 frames/);
assert.match(prepared, /임의 비트 2개 손상 \(Seed 7\)/);

const transmitting = formatEventLog({
    type: 'TX_STATUS',
    sessionId,
    payload: {
        percent: 57,
        stage: 'Transmitting',
        counters: {
            frameNumber: 2,
            totalFrames: 4,
            sentCopies: 2,
            repeatCount: 3,
            droppedCopyIndexes: [1],
            injectionMode: 'RANDOM_BIT_ERROR',
            injectedBitPositions: [421, 1530],
        },
    },
});
assert.match(transmitting, /프레임 2\/4/);
assert.match(transmitting, /Sender 미전송 복제본 #2/);
assert.match(transmitting, /AFS frame bit 위치 \[421, 1530\]/);

const receiving = formatEventLog({
    type: 'RX_STATUS',
    sessionId,
    payload: {
        percent: 45,
        stage: 'Receiving',
        counters: {
            frameIndex: 1,
            receivedFrames: 2,
            expectedFrames: 4,
            receivedDatagrams: 7,
            duplicateDatagrams: 4,
            corruptDatagrams: 0,
            invalidDatagrams: 0,
        },
    },
});
assert.match(receiving, /RX 45%/);
assert.match(receiving, /누적 2\/4 frames/);
assert.match(receiving, /중복 4, UDP 해석 실패 0/);

const result = formatEventLog({
    type: 'RESULT',
    role: 'RECEIVER',
    sessionId,
    payload: {
        role: 'RECEIVER',
        verdict: 'PASS',
        counters: {
            expectedLogicalFrames: 4,
            receivedLogicalFrames: 4,
            sentDatagrams: 12,
            receivedDatagrams: 12,
            duplicateDatagrams: 8,
            corruptDatagrams: 0,
            invalidDatagrams: 0,
            decodeFailedFrames: 0,
            injectedBitCount: 4,
            syncRejectedFrames: 0,
            simulatedDroppedDatagrams: 0,
        },
        integrity: {
            success: true,
            sourceLength: 4096,
            reconstructedLength: 4096,
            expectedRecords: 8,
            reconstructedRecords: 8,
            sourceSha256: 'same',
            reconstructedSha256: 'same',
        },
        metrics: [
            {
                name: 'DecodedFrames',
                value: 4,
                unit: 'frame',
                status: 'PASS',
            },
            {
                name: 'FullyDecodedFrames',
                value: 4,
                unit: 'frame',
                status: 'PASS',
            },
        ],
    },
});
assert.match(result, /RECEIVER 최종 판정 PASS/);
assert.match(result, /프레임 4\/4/);
assert.match(result, /UDP 해석 실패 0 · AFS 복호화 실패 0/);
assert.match(result, /시험 주입 오류 4 bit/);
assert.match(result, /SHA-256 일치/);
assert.match(result, /Decoder 처리 프레임 4 frame \(정상\)/);
assert.match(result, /CRC 통과 완전 복호 프레임 4 frame \(정상\)/);

const syncSessionId = 'test-d-event-log-regression';
formatEventLog({
    type: 'RX_STATUS',
    sessionId: syncSessionId,
    payload: {
        message: 'TEST_D_SYNC_RECOVERY session started',
        counters: {
            testType: 'TEST_D_SYNC_RECOVERY',
            expectedFrames: 4,
            sourceBytes: 464,
            recordCount: 4,
            errorCount: 1,
            syncDamageInterval: 10,
            injectedFrameCount: 1,
        },
    },
});
const syncVerifying = formatEventLog({
    type: 'RX_STATUS',
    sessionId: syncSessionId,
    payload: {
        stage: 'Verifying',
        percent: 95,
        counters: {
            integritySuccess: false,
            reconstructedLength: 348,
            sourceLength: 464,
            reconstructedRecords: 3,
            expectedRecords: 4,
        },
    },
});
assert.match(syncVerifying, /Test D 예상 부분 복원/);
assert.doesNotMatch(syncVerifying, /무결성 검증 실패/);

const syncResult = formatEventLog({
    type: 'RESULT',
    role: 'RECEIVER',
    sessionId: syncSessionId,
    payload: {
        role: 'RECEIVER',
        verdict: 'PASS',
        counters: {},
        integrity: {
            success: false,
            sourceLength: 464,
            reconstructedLength: 348,
            expectedRecords: 4,
            reconstructedRecords: 3,
            sourceSha256: 'source',
            reconstructedSha256: 'partial',
        },
    },
});
assert.match(syncResult, /GRAW 부분 복원 \(Test D 예상\)/);

console.log('event-log smoke test passed');
