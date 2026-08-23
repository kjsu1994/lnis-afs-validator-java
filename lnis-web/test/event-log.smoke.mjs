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
assert.match(transmitting, /의도적 Drop 복제본 #2/);
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
        },
    },
});
assert.match(receiving, /RX 45%/);
assert.match(receiving, /누적 2\/4 frames/);
assert.match(receiving, /중복 4, 손상 0/);

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
        ],
    },
});
assert.match(result, /RECEIVER 최종 판정 PASS/);
assert.match(result, /프레임 4\/4/);
assert.match(result, /SHA-256 일치/);
assert.match(result, /복호화 프레임 4 frame \(PASS\)/);

console.log('event-log smoke test passed');
