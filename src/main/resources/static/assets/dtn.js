import {
    createPayloadViewer
} from './dtn-payload.js';


const api =
    '/lnis/api/v1';


const $ =
    id =>
        document.getElementById(id);


const payloadViewer =
    createPayloadViewer(
        $('dtn-payload')
    );



/* =========================================================
 * STATE
 * ========================================================= */

let agentCache = [];

let inputId = null;

let testId = null;


/*
 * 시험 데이터 유형
 */
let selectedTestType =
    'AFS_METADATA';


/*
 * DTN / HDTN 전송 방식
 *
 * 현재는 화면 선택값만 관리한다.
 * 실제 Adapter REST 호출은 추후 구현한다.
 */
let selectedSenderMode =
    'DTN';

let selectedReceiverMode =
    'HDTN';


let busy = false;



/* =========================================================
 * API
 * ========================================================= */

async function request(
    path,
    options = {}
) {

    const response =
        await fetch(
            api + path,
            options
        );


    const body =
        await response
            .json()
            .catch(
                () => ({})
            );


    if (!response.ok) {

        throw new Error(
            body.detail
            || body.message
            || `HTTP ${response.status}`
        );

    }


    return body;
}



function post(
    path,
    body
) {

    return request(
        path,
        {
            method: 'POST',

            headers: {
                'Content-Type':
                    'application/json'
            },

            body:
                body === undefined
                    ? undefined
                    : JSON.stringify(body)
        }
    );

}



/* =========================================================
 * LOG
 * ========================================================= */

function log(
    message,
    level = 'INFO'
) {

    const now =
        new Date();


    const time =
        now.toLocaleTimeString(
            'ko-KR',
            {
                hour12: false,

                hour:
                    '2-digit',

                minute:
                    '2-digit',

                second:
                    '2-digit'
            }
        );


    const line =
        `${time} [${level}] ${message}`;


    const target =
        $('dtn-log');


    if (!target)
        return;


    target.textContent +=
        `${line}\n`;


    target.scrollTop =
        target.scrollHeight;

}



$('dtn-log-clear').onclick =
    () => {

        $('dtn-log')
            .textContent = '';

    };



/* =========================================================
 * STATUS
 * ========================================================= */

function setDot(
    id,
    online
) {

    const dot =
        $(id);


    if (!dot)
        return;


    dot.classList.toggle(
        'online',
        online
    );


    dot.classList.toggle(
        'offline',
        !online
    );

}



function setPill(
    id,
    text,
    state = ''
) {

    const target =
        $(id);


    if (!target)
        return;


    target.textContent =
        text;


    target.className =
        `pill ${state}`.trim();

}



/* =========================================================
 * AGENT
 * ========================================================= */

function fillAgentSelect(
    selectId,
    role
) {

    const select =
        $(selectId);


    if (!select)
        return null;


    const old =
        select.value;


    const agents =
        agentCache.filter(
            agent =>
                agent.role === role
        );


    select.replaceChildren();


    agents.forEach(
        agent => {

            select.add(
                new Option(
                    agent.agentId,
                    agent.agentId
                )
            );

        }
    );


    if (
        [...select.options]
            .some(
                option =>
                    option.value === old
            )
    ) {

        select.value =
            old;

    }


    return agents[0]
        || null;

}



function applyReceiverAddress() {

    const receiverSelect =
        $('dtn-receiver');


    if (!receiverSelect)
        return;


    const receiver =
        agentCache.find(
            agent =>
                agent.agentId
                === receiverSelect.value
        );


    if (!receiver)
        return;


    const addresses =
        receiver.ipv4Addresses
            ?.filter(Boolean)
        || [];


    if (!addresses.length)
        return;


    const serverOctets =
        location.hostname
            .split('.');


    const prefix =
        serverOctets.length === 4
            ? `${
                serverOctets
                    .slice(0, 3)
                    .join('.')
            }.`
            : null;


    const best =
        addresses.find(
            address =>
                prefix
                &&
                address.startsWith(prefix)
        )
        || addresses[0];


    const ipInput =
        $('dtn-receiver-ip');


    if (ipInput) {

        ipInput.value =
            best;

    }

}



async function refreshAgents() {

    agentCache =
        await request(
            '/agents'
        );


    const sender =
        fillAgentSelect(
            'dtn-sender',
            'SENDER'
        );


    const receiver =
        fillAgentSelect(
            'dtn-receiver',
            'RECEIVER'
        );


    const senderReady =
        sender?.state
        === 'READY';


    const receiverReady =
        receiver?.state
        === 'READY';



    $('sender-agent-name')
        .textContent =
        sender?.agentId
        || '-';


    $('receiver-agent-name')
        .textContent =
        receiver?.agentId
        || '-';



    setDot(
        'sender-dot',
        senderReady
    );


    setDot(
        'receiver-dot',
        receiverReady
    );



    setPill(
        'dtn-sender-status',

        senderReady
            ? '송신 서비스 연결됨'
            : '송신 서비스 연결 안 됨',

        senderReady
            ? 'online'
            : 'error'
    );


    setPill(
        'dtn-receiver-status',

        receiverReady
            ? '수신 서비스 연결됨'
            : '수신 서비스 연결 안 됨',

        receiverReady
            ? 'online'
            : 'error'
    );


    applyReceiverAddress();


    updateControls();

}



/* =========================================================
 * TEST TYPE
 * ========================================================= */

document
    .querySelectorAll(
        '.test-type-button'
    )
    .forEach(
        button => {

            button.onclick =
                () => {

                    document
                        .querySelectorAll(
                            '.test-type-button'
                        )
                        .forEach(
                            item =>
                                item.classList
                                    .remove(
                                        'active'
                                    )
                        );


                    button.classList
                        .add(
                            'active'
                        );


                    selectedTestType =
                        button.dataset
                            .testType;


                    log(
                        `시험 유형 선택: ${
                            button
                                .textContent
                                .trim()
                        }`
                    );


                    updateControls();

                };

        }
    );


/* =========================================================
 * DTN / HDTN TRANSPORT MODE
 *
 * 현재는 화면 선택 상태만 변경한다.
 * 실제 Adapter REST 연동은 추후 구현한다.
 * ========================================================= */

document
    .querySelectorAll(
        '.transport-mode-button'
    )
    .forEach(
        button => {

            button.onclick =
                () => {

                    /*
                     * 기존 선택 표시 제거
                     */
                    document
                        .querySelectorAll(
                            '.transport-mode-button'
                        )
                        .forEach(
                            item =>
                                item.classList
                                    .remove('active')
                        );


                    /*
                     * 클릭한 버튼 활성화
                     */
                    button.classList
                        .add('active');


                    /*
                     * 선택값 저장
                     */
                    selectedSenderMode =
                        button.dataset
                            .senderMode;


                    selectedReceiverMode =
                        button.dataset
                            .receiverMode;

                    /*
                     * 우측 상단 문구 변경
                     *
                     * 페이지 최초 로딩 시에는 실행되지 않는다.
                     */
                    const state =
                        $('dtn-transport-mode-state');


                    if (state) {

                        state.textContent =
                            `${selectedSenderMode} → ${selectedReceiverMode}`;

                    }


                    log(
                        `전송 방식 선택: `
                        + `${selectedSenderMode} → ${selectedReceiverMode}`
                    );

                };

        }
    );

/* =========================================================
 * INPUT MODE
 * ========================================================= */

document
    .querySelectorAll(
        '.input-mode'
    )
    .forEach(
        button => {

            button.onclick =
                () => {

                    document
                        .querySelectorAll(
                            '.input-mode'
                        )
                        .forEach(
                            item =>
                                item.classList
                                    .remove(
                                        'active'
                                    )
                        );


                    button.classList
                        .add(
                            'active'
                        );


                    const capture =
                        button.dataset.inputMode
                        === 'capture';


                    $('dtn-capture-panel')
                        .classList.toggle(
                        'hidden',
                        !capture
                    );


                    $('dtn-upload-panel')
                        .classList.toggle(
                        'hidden',
                        capture
                    );

                };

        }
    );



/* =========================================================
 * COM PORT
 * ========================================================= */

$('dtn-refresh').onclick =
    async () => {

        try {

            const senderId =
                $('dtn-sender')
                    .value;


            if (!senderId) {

                throw new Error(
                    '송신 서비스가 연결되어 있지 않습니다.'
                );

            }


            await post(
                `/agents/${
                    encodeURIComponent(
                        senderId
                    )
                }/serial-ports/refresh`
            );


            log(
                `COM 포트 조회 요청: ${senderId}`
            );

        }
        catch (error) {

            log(
                error.message,
                'ERROR'
            );

        }

    };



/* =========================================================
 * WebSocket
 * ========================================================= */

let socket;



function connectSocket() {

    const protocol =
        location.protocol === 'https:'
            ? 'wss://'
            : 'ws://';


    socket =
        new WebSocket(
            protocol
            + location.host
            + '/lnis/ws/status'
        );



    socket.onopen =
        () => {

            setPill(
                'dtn-server-status',
                '서버 연결됨',
                'online'
            );


            log(
                '중앙 서버 WebSocket 연결'
            );

        };



    socket.onclose =
        () => {

            setPill(
                'dtn-server-status',
                '서버 연결 끊김',
                'error'
            );


            log(
                '중앙 서버 연결 끊김',
                'ERROR'
            );


            setTimeout(
                connectSocket,
                2000
            );

        };



    socket.onmessage =
        event => {

            const data =
                JSON.parse(
                    event.data
                );


            /*
             * 기존 Agent COM 포트 응답 재사용
             */
            if (
                data.agentId
                === $('dtn-sender').value
                &&
                data.payload?.ports
            ) {

                $('dtn-port')
                    .replaceChildren(

                        new Option(
                            '포트 선택',
                            ''
                        ),

                        ...data.payload
                            .ports
                            .map(
                                port =>
                                    new Option(
                                        port.name,
                                        port.name
                                    )
                            )

                    );


                log(
                    `COM 포트 ${
                        data.payload
                            .ports
                            .length
                    }개 확인`
                );

            }

        };

}



/* =========================================================
 * GRAW UPLOAD
 * ========================================================= */

$('dtn-upload').onclick =
    async () => {

        const file =
            $('dtn-graw-file')
                .files[0];


        if (!file) {

            log(
                'capture.graw 파일을 선택하세요.',
                'WARN'
            );

            return;

        }


        try {

            busy = true;


            updateControls();


            $('dtn-upload-progress')
                .value = 0;



            /*
             * 1.
             * Input 메타데이터 등록
             *
             * POST /inputs
             * Content-Type: application/json
             */
            const input =
                await post(
                    '/inputs',
                    {
                        fileName:
                        file.name,

                        size:
                        file.size,

                        kind:
                            'GRAW_UPLOAD'
                    }
                );


            inputId =
                input.inputId;



            /*
             * 2.
             * 실제 GRAW binary 업로드
             */
            const chunkSize =
                1024 * 1024;



            for (
                let offset = 0,
                    index = 0;

                offset < file.size;

                offset += chunkSize,
                    index++
            ) {

                const bytes =
                    new Uint8Array(
                        await file
                            .slice(
                                offset,
                                offset
                                + chunkSize
                            )
                            .arrayBuffer()
                    );


                /*
                 * PUT /inputs/{inputId}/chunks/{index}
                 *
                 * Backend consumes:
                 * application/octet-stream
                 */
                await request(
                    `/inputs/${inputId}/chunks/${index}`,
                    {
                        method:
                            'PUT',

                        headers: {
                            'Content-Type':
                                'application/octet-stream'
                        },

                        body:
                        bytes
                    }
                );


                $('dtn-upload-progress')
                    .value =
                    Math.round(
                        (
                            Math.min(
                                file.size,
                                offset
                                + bytes.length
                            )
                            /
                            file.size
                        )
                        * 100
                    );

            }



            /*
             * 3.
             * 입력 완료
             */
            const complete =
                await request(
                    `/inputs/${inputId}/complete`,
                    {
                        method:
                            'POST'
                    }
                );


            $('dtn-input-state')
                .textContent =
                `${
                    complete.recordCount
                } records`;


            log(
                `GRAW 입력 완료: ${
                    complete.recordCount
                } records`
            );


            updateControls();

        }
        catch (error) {

            inputId =
                null;


            $('dtn-input-state')
                .textContent =
                '입력 실패';


            log(
                error.message,
                'ERROR'
            );

        }
        finally {

            busy = false;


            updateControls();

        }

    };



/* =========================================================
 * ONE-SHOT GNSS CAPTURE
 *
 * 현재 Backend에는 정확한 1 Epoch용
 * API가 없기 때문에 화면 이벤트까지만 유지한다.
 * ========================================================= */

$('dtn-start').onclick =
    async () => {

        if (
            selectedTestType
            !== 'AFS_METADATA'
            &&
            selectedTestType
            !== 'GNSS_RAW'
        ) {

            log(
                'I/Q Sample 수집 기능은 아직 구현되지 않았습니다.',
                'WARN'
            );

            return;

        }


        const port =
            $('dtn-port')
                .value;


        if (!port) {

            log(
                'COM 포트를 선택하세요.',
                'WARN'
            );

            return;

        }


        log(
            `GNSS 단일 Epoch 수집 요청: ${port}`
        );


        /*
         * TODO
         *
         * 향후 Backend:
         *
         * POST /dtn/captures/one-shot
         *
         * 구현 후 실제 REST 호출 연결
         */

    };



/* =========================================================
 * DESTINATION
 * ========================================================= */

function buildSendUrl() {

    const ip =
        $('dtn-receiver-ip')
            .value
            .trim();


    const port =
        Number(
            $('dtn-receiver-port')
                .value
        );


    if (
        !ip
        ||
        !port
    ) {

        return null;

    }


    /*
     * 현재 Adapter endpoint 유지
     */
    return `http://${ip}:${port}/transfers`;

}



function updateDestinationState() {

    const receiver =
        agentCache.find(
            agent =>
                agent.agentId
                === $('dtn-receiver')
                    .value
        );


    const online =
        receiver?.state
        === 'READY';


    setDot(
        'destination-dot',
        online
    );


    $('destination-state')
        .textContent =
        online
            ? '연결됨'
            : '연결 안 됨';

}



$('dtn-receiver-ip').oninput =
    updateControls;


$('dtn-receiver-port').oninput =
    updateControls;



/* =========================================================
 * SEND
 * ========================================================= */

$('dtn-send').onclick =
    async () => {


        /*
         * 현재 실제 Backend에서 구현된 전송 흐름은
         * AFS Frame + Metadata만 사용한다.
         */
        if (
            selectedTestType
            !== 'AFS_METADATA'
        ) {

            log(

                selectedTestType
                === 'GNSS_RAW'

                    ? 'GNSS RAW Data DTN 전송 기능은 아직 구현되지 않았습니다.'

                    : 'I/Q Sample DTN 전송 기능은 아직 구현되지 않았습니다.',

                'WARN'
            );


            return;

        }



        try {

            busy = true;


            updateControls();


            const sendUrl =
                buildSendUrl();



            const result =
                await post(
                    '/dtn/tests',
                    {
                        inputId,


                        senderAgentId:
                        $('dtn-sender')
                            .value,


                        receiverAgentId:
                        $('dtn-receiver')
                            .value,


                        sendUrl
                    }
                );



            testId =
                result.testId;



            payloadViewer.setJob(
                result
            );



            $('dtn-result')
                .textContent =
                result.message
                ||
                result.state;



            setPill(
                'dtn-test-status',
                '시험 진행 중',
                'warning'
            );



            log(
                `DTN 시험 시작: ${testId}`,
                'SEND'
            );


            /*
             * 현재 선택된 전송 방식은
             * 아직 Adapter REST에 전달하지 않는다.
             *
             * 추후 Backend 구현 시:
             *
             * selectedSenderMode
             * selectedReceiverMode
             *
             * 두 값을 전달하도록 확장한다.
             */
            log(
                `선택된 전송 방식: `
                + `${selectedSenderMode} → ${selectedReceiverMode}`
            );

        }
        catch (error) {

            log(
                error.message,
                'ERROR'
            );

        }
        finally {

            busy = false;


            updateControls();

        }

    };



/* =========================================================
 * PVT
 * ========================================================= */

function formatNumber(
    value,
    digits = 3
) {

    if (
        value === null
        ||
        value === undefined
        ||
        Number.isNaN(
            Number(value)
        )
    ) {

        return '-';

    }


    return Number(value)
        .toFixed(
            digits
        );

}



function renderPvt(
    pvt
) {

    if (!pvt) {

        return;

    }



    $('dtn-gnss-time')
        .textContent =
        `Week ${pvt.week} / `
        + `TOW ${
            formatNumber(
                pvt.towSeconds,
                3
            )
        } s`;



    const ecef =
        pvt.ecefMeters
        || [];


    const velocity =
        pvt.velocityMetersPerSecond
        || [];



    $('pvt-x').textContent =
        formatNumber(
            ecef[0]
        );


    $('pvt-y').textContent =
        formatNumber(
            ecef[1]
        );


    $('pvt-z').textContent =
        formatNumber(
            ecef[2]
        );



    $('pvt-vx').textContent =
        formatNumber(
            velocity[0]
        );


    $('pvt-vy').textContent =
        formatNumber(
            velocity[1]
        );


    $('pvt-vz').textContent =
        formatNumber(
            velocity[2]
        );



    $('pvt-satellites')
        .textContent =
        pvt.satellitesUsed
        ?? '-';



    $('pvt-clock')
        .textContent =
        formatNumber(
            pvt.receiverClockBiasSeconds,
            9
        );

}



/* =========================================================
 * POLLING
 * ========================================================= */

async function pollTest() {

    if (!testId) {

        setTimeout(
            pollTest,
            2000
        );


        return;

    }



    try {

        const result =
            await request(
                `/dtn/tests/${testId}`
            );



        payloadViewer.setJob(
            result
        );



        $('dtn-result')
            .textContent =
            [
                `상태 : ${
                    result.state
                }`,

                `DTN 수신 : ${
                    result.dtnReceived
                        ? '완료'
                        : '대기'
                }`,

                result.verdict
                    ? `판정 : ${
                        result.verdict
                    }`
                    : ''
            ]
                .filter(
                    Boolean
                )
                .join(
                    '\n'
                );



        if (
            result.dtnReceived
        ) {

            log(
                `DTN 수신 확인: ${testId}`,
                'RECV'
            );

        }

        if (
            result.verdict
        ) {

            const report =
                await request(
                    `/dtn/tests/${testId}/report`
                );



            /*
             * referencePvt = List<Pvt>
             */
            renderPvt(
                report.referencePvt?.[0]
            );



            setPill(
                'dtn-test-status',

                `시험 완료 · ${
                    result.verdict
                }`,

                result.verdict
                === 'PASS'

                    ? 'online'

                    : 'error'
            );



            $('dtn-report').href =
                `${api}/dtn/tests/${testId}/report`;


            $('dtn-report').hidden =
                false;

        }

    }
    catch (error) {

        log(
            `시험 상태 조회 실패: ${error.message}`,
            'ERROR'
        );

    }



    setTimeout(
        pollTest,
        2000
    );

}
/* =========================================================
 * CONTROL
 * ========================================================= */

function updateControls() {

    const sender =
        agentCache.find(
            agent =>
                agent.agentId
                === $('dtn-sender')
                    .value
        );


    const receiver =
        agentCache.find(
            agent =>
                agent.agentId
                === $('dtn-receiver')
                    .value
        );

    $('dtn-start').disabled =
        busy
        ||
        sender?.state
        !== 'READY';



    $('dtn-send').disabled =
        busy
        ||
        !inputId
        ||
        sender?.state
        !== 'READY'
        ||
        receiver?.state
        !== 'READY'
        ||
        !buildSendUrl();



    /*
     * 전송 방식 버튼은
     * 시험 처리 중에만 잠근다.
     */
    document
        .querySelectorAll(
            '.transport-mode-button'
        )
        .forEach(
            button => {

                button.disabled =
                    busy;

            }
        );



    updateDestinationState();

}



/* =========================================================
 * INIT
 * ========================================================= */

async function init() {

    try {

        const config =
            await request(
                '/dtn/config'
            );



        /*
         * 기존 전체 URL 설정값이 있으면
         * 사용자 화면에는 IP / Port로 분리하여 표시
         */
        if (
            config.defaultSendUrl
        ) {

            try {

                const url =
                    new URL(
                        config.defaultSendUrl
                    );


                $('dtn-receiver-ip')
                    .value =
                    url.hostname;


                $('dtn-receiver-port')
                    .value =
                    url.port
                    ||
                    (
                        url.protocol
                        === 'https:'

                            ? 443

                            : 80
                    );

            }
            catch {

                /*
                 * 설정값 파싱 실패 시
                 * 입력 화면의 기본값을 유지한다.
                 */

            }

        }

        await refreshAgents();

        connectSocket();

        log(
            'DTN Sender 화면 준비 완료'
        );

        pollTest();

    }
    catch (error) {

        log(
            error.message,
            'ERROR'
        );

    }

}

init();