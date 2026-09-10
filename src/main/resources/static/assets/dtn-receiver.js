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


let tests = [];

let agents = [];



async function get(path) {

  const response =
      await fetch(
          api + path,
          {
            cache: 'no-store'
          }
      );


  if (!response.ok)
    throw new Error(
        `HTTP ${response.status}`
    );


  return response.json();

}



function log(
    message,
    level = 'INFO'
) {

  const time =
      new Date()
          .toLocaleTimeString(
              'ko-KR',
              {
                hour12: false,
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
              }
          );


  const target =
      $('dtn-log');


  target.textContent +=
      `${time} [${level}] ${message}\n`;


  target.scrollTop =
      target.scrollHeight;

}


$('dtn-log-clear').onclick =
    () => {

      $('dtn-log').textContent = '';

    };



function dot(
    id,
    online
) {

  const element =
      $(id);


  element.classList.toggle(
      'online',
      online
  );


  element.classList.toggle(
      'offline',
      !online
  );

}



function pill(
    id,
    text,
    state = ''
) {

  const element =
      $(id);


  element.textContent =
      text;


  element.className =
      `pill ${state}`.trim();

}



function formatNumber(
    value,
    digits = 3
) {

  if (
      value === undefined
      || value === null
  )
    return '-';


  return Number(value)
      .toFixed(digits);

}



function renderPvt(pvt) {

  if (!pvt)
    return;


  $('dtn-gnss-time')
      .textContent =
      `Week ${pvt.week} / TOW ${formatNumber(pvt.towSeconds)} s`;


  const xyz =
      pvt.ecefMeters
      || [];


  const velocity =
      pvt.velocityMetersPerSecond
      || [];


  $('pvt-x').textContent =
      formatNumber(xyz[0]);


  $('pvt-y').textContent =
      formatNumber(xyz[1]);


  $('pvt-z').textContent =
      formatNumber(xyz[2]);


  $('pvt-vx').textContent =
      formatNumber(velocity[0]);


  $('pvt-vy').textContent =
      formatNumber(velocity[1]);


  $('pvt-vz').textContent =
      formatNumber(velocity[2]);


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



function paintAgents() {

  const sender =
      agents.find(
          agent =>
              agent.role === 'SENDER'
      );


  const receiver =
      agents.find(
          agent =>
              agent.role === 'RECEIVER'
      );


  const senderReady =
      sender?.state === 'READY';


  const receiverReady =
      receiver?.state === 'READY';


  $('sender-agent-name')
      .textContent =
      sender?.agentId || '-';


  $('receiver-agent-name')
      .textContent =
      receiver?.agentId || '-';


  dot(
      'sender-dot',
      senderReady
  );


  dot(
      'receiver-dot',
      receiverReady
  );


  dot(
      'destination-dot',
      senderReady
      && receiverReady
  );


  pill(
      'dtn-sender-status',
      senderReady
          ? '송신 서비스 연결됨'
          : '송신 서비스 연결 안 됨',
      senderReady
          ? 'online'
          : 'error'
  );


  pill(
      'dtn-receiver-status',
      receiverReady
          ? '수신 서비스 연결됨'
          : '수신 서비스 연결 안 됨',
      receiverReady
          ? 'online'
          : 'error'
  );


  $('destination-state')
      .textContent =
      senderReady
      && receiverReady
          ? '연결됨'
          : '연결 안 됨';


  const address =
      receiver?.ipv4Addresses?.[0];


  $('receiver-ip').value =
      address || '-';

}



async function renderTest() {

  const testId =
      $('dtn-tests').value;


  const job =
      tests.find(
          item =>
              item.testId === testId
      );


  payloadViewer.setJob(job);


  if (!job) {

    $('dtn-result')
        .textContent =
        '시험 대기';


    $('receive-state')
        .textContent =
        '데이터 없음';


    return;

  }


  $('receive-state')
      .textContent =
      job.dtnReceived
          ? '수신 완료'
          : '수신 대기';


  $('dtn-process')
      .disabled =
      !job.dtnReceived;


  $('dtn-result')
      .textContent =
      [
        `시험 : ${job.testId}`,
        `상태 : ${job.state}`,
        `DTN 수신 : ${job.dtnReceived ? '완료' : '대기'}`,
        job.verdict
            ? `판정 : ${job.verdict}`
            : ''
      ]
          .filter(Boolean)
          .join('\n');


  $('dtn-report').href =
      `${api}/dtn/tests/${job.testId}/report`;


  $('dtn-report').hidden =
      false;


  if (job.dtnReceived) {

    try {

      const report =
          await get(
              `/dtn/tests/${job.testId}/report`
          );


      renderPvt(
          report.receivedPvt?.[0]
      );

    }
    catch (error) {

      log(
          `PVT 조회 실패: ${error.message}`,
          'ERROR'
      );

    }

  }

}



/*
 * 현재 Receiver Agent에서 DTN 수신 후
 * PVT 계산은 자동 수행된다.
 *
 * 따라서 이 버튼은 현재는 결과 재조회 역할만 한다.
 *
 * 추후 별도 PVT 계산 API가 생기면
 * 여기만 POST 요청으로 변경하면 된다.
 */
$('dtn-process').onclick =
    async () => {

      log(
          '수신 PVT 결과 조회'
      );


      await renderTest();

    };



$('dtn-tests').onchange =
    renderTest;



async function poll() {

  try {

    const [
      nextAgents,
      nextTests,
      config
    ] =
        await Promise.all([
          get('/agents'),
          get('/dtn/tests'),
          get('/dtn/config')
        ]);


    agents =
        nextAgents;


    tests =
        nextTests;


    pill(
        'dtn-server-status',
        '서버 연결됨',
        'online'
    );


    paintAgents();


    if (config.receivePort) {

      $('receiver-port').value =
          config.receivePort;

    }


    const selected =
        $('dtn-tests').value;


    $('dtn-tests')
        .replaceChildren(
            ...tests.map(
                job =>
                    new Option(
                        `${job.createdAt} · ${job.state}`,
                        job.testId
                    )
            )
        );


    if (
        tests.some(
            job =>
                job.testId === selected
        )
    ) {

      $('dtn-tests').value =
          selected;

    }


    await renderTest();

  }
  catch (error) {

    pill(
        'dtn-server-status',
        '서버 연결 실패',
        'error'
    );


    log(
        error.message,
        'ERROR'
    );

  }
  finally {

    setTimeout(
        poll,
        2000
    );

  }

}


log(
    'DTN Receiver 화면 시작'
);


poll();