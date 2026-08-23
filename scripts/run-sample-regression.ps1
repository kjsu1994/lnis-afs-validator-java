param(
    [string]$BaseUrl = 'http://127.0.0.1:8088/lnis/api/v1',
    [string]$SamplePath = 'C:\Users\honeybadger\Desktop\Lnis\sample-data\dummy-capture.graw',
    [string[]]$TestTypes = @(
        'TEST_A_NORMAL',
        'TEST_B_RANDOM_ERRORS',
        'TEST_C_BURST_ERRORS',
        'TEST_D_SYNC_RECOVERY',
        'TEST_E_UDP_DROP'
    )
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new()
$OutputEncoding = [Text.UTF8Encoding]::new()
$terminalStates = @('COMPLETED', 'CANCELLED', 'FAILED', 'INCONCLUSIVE')

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw "회귀시험 검증 실패: $Message"
    }
}

function Get-MetricValue {
    param(
        [object]$Result,
        [string]$Name
    )

    return ($Result.metrics | Where-Object { $_.name -eq $Name } | Select-Object -First 1).value
}

function Wait-AgentsReady {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $connected = @(Invoke-RestMethod -Uri "$BaseUrl/agents")
        $senderReady = $connected | Where-Object {
            $_.agentId -eq 'sender-1' -and $_.state -eq 'READY'
        }
        $receiverReady = $connected | Where-Object {
            $_.agentId -eq 'receiver-1' -and $_.state -eq 'READY'
        }
        if ($senderReady -and $receiverReady) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw 'Sender/Receiver Agent가 30초 안에 READY 상태가 되지 않았습니다.'
}

function Upload-Sample {
    $sample = Get-Item -LiteralPath $SamplePath
    $createBody = @{
        fileName = $sample.Name
        size = $sample.Length
        kind = 'GRAW_UPLOAD'
    } | ConvertTo-Json
    $input = Invoke-RestMethod `
        -Uri "$BaseUrl/inputs" `
        -Method Post `
        -ContentType 'application/json' `
        -Body $createBody
    $bytes = [IO.File]::ReadAllBytes($sample.FullName)
    Invoke-RestMethod `
        -Uri "$BaseUrl/inputs/$($input.inputId)/chunks/0" `
        -Method Put `
        -ContentType 'application/octet-stream' `
        -Body $bytes | Out-Null
    $complete = Invoke-RestMethod `
        -Uri "$BaseUrl/inputs/$($input.inputId)/complete" `
        -Method Post

    Assert-Condition ($complete.receivedSize -eq 464) '샘플 크기가 464 byte가 아닙니다.'
    Assert-Condition ($complete.recordCount -eq 4) '샘플 GRAW 레코드가 4개가 아닙니다.'
    return $complete
}

function Start-TestSession {
    param(
        [object]$InputBuffer,
        [object]$Definition
    )

    Wait-AgentsReady
    $body = @{
        senderAgentId = 'sender-1'
        receiverAgentId = 'receiver-1'
        # PowerShell 자동 변수 $input과 충돌하지 않도록 입력 객체는 InputBuffer라는 이름을 사용한다.
        inputId = $InputBuffer.inputId
        transport = @{
            broadcastAddress = '127.0.0.1'
            dataPort = 45821
            resultPort = 45822
            repeatCount = $Definition.RepeatCount
            resultTimeoutSeconds = 30
            endGraceMilliseconds = 1000
            probeIntervalMilliseconds = 1000
        }
        options = @{
            testType = $Definition.Type
            errorCount = $Definition.ErrorCount
            errorSeed = 1
            syncDamageInterval = 10
            dropRatePercent = $Definition.DropRate
            dropSeed = 1
            thresholds = @{}
        }
    } | ConvertTo-Json -Depth 8

    try {
        $session = Invoke-RestMethod `
            -Uri "$BaseUrl/sessions" `
            -Method Post `
            -ContentType 'application/json' `
            -Body $body
    } catch {
        $response = $_.Exception.Response
        if ($response) {
            $reader = [IO.StreamReader]::new($response.GetResponseStream())
            $detail = $reader.ReadToEnd()
            $reader.Dispose()
            throw "세션 생성 API 오류: $detail"
        }
        throw
    }
    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 250
        $session = Invoke-RestMethod -Uri "$BaseUrl/sessions/$($session.sessionId)"
    } while ($terminalStates -notcontains $session.state -and (Get-Date) -lt $deadline)

    Assert-Condition ($terminalStates -contains $session.state) "$($Definition.Type)이 제한 시간 안에 끝나지 않았습니다."
    return $session
}

function Test-SessionResult {
    param(
        [object]$Session,
        [object]$Definition
    )

    Assert-Condition ($Session.state -eq 'COMPLETED') "$($Definition.Type) 세션 상태가 $($Session.state)입니다."
    Assert-Condition ($Session.verdict -eq 'PASS') "$($Definition.Type) 최종 판정이 $($Session.verdict)입니다."
    Assert-Condition ($Session.txResult.verdict -eq 'PASS') "$($Definition.Type) Sender 판정이 PASS가 아닙니다."
    Assert-Condition ($Session.rxResult.verdict -eq 'PASS') "$($Definition.Type) Receiver 판정이 PASS가 아닙니다."
    Assert-Condition ($Session.rxResult.counters.expectedLogicalFrames -eq 4) "$($Definition.Type) 예상 프레임이 4개가 아닙니다."
    Assert-Condition ($Session.rxResult.counters.receivedLogicalFrames -eq 4) "$($Definition.Type) 논리 프레임 4개를 모두 받지 못했습니다."
    Assert-Condition ($Session.rxResult.counters.corruptDatagrams -eq 0) "$($Definition.Type)에 손상 데이터그램이 있습니다."
    Assert-Condition ($Session.rxResult.counters.invalidDatagrams -eq 0) "$($Definition.Type)에 UDP 패킷 해석 실패가 있습니다."
    Assert-Condition ($Session.rxResult.counters.decodeFailedFrames -eq 0) "$($Definition.Type)에 AFS 복호화 실패 프레임이 있습니다."

    $decoded = Get-MetricValue $Session.rxResult 'DecodedFrames'
    $fullyDecoded = Get-MetricValue $Session.rxResult 'FullyDecodedFrames'
    if ($Definition.Type -eq 'TEST_D_SYNC_RECOVERY') {
        $recovered = Get-MetricValue $Session.rxResult 'RecoveredSyncFrames'
        Assert-Condition (-not $Session.rxResult.integrity.success) 'Test D는 손상 프레임 제외로 전체 무결성이 불일치해야 합니다.'
        Assert-Condition ($decoded -eq 3) 'Test D에서 손상 1개를 제외한 3개 프레임이 복호화되지 않았습니다.'
        Assert-Condition ($fullyDecoded -eq 3) 'Test D의 정상 동기 프레임 3개가 CRC까지 통과하지 못했습니다.'
        Assert-Condition ($recovered -eq 3) 'Test D에서 다음 동기 패턴 3개를 복구하지 못했습니다.'
        Assert-Condition ($Session.rxResult.integrity.reconstructedLength -eq 348) 'Test D 부분 복원 크기가 348 byte가 아닙니다.'
        Assert-Condition ($Session.rxResult.integrity.reconstructedRecords -eq 3) 'Test D 부분 복원 레코드가 3개가 아닙니다.'
        Assert-Condition ($Session.rxResult.counters.injectedBitCount -eq 1) 'Test D 주입 오류가 1 bit가 아닙니다.'
        Assert-Condition ($Session.rxResult.counters.syncRejectedFrames -eq 1) 'Test D 동기 손상 제외 프레임이 1개가 아닙니다.'
    } else {
        Assert-Condition $Session.rxResult.integrity.success "$($Definition.Type) 원본/복원 SHA-256이 일치하지 않습니다."
        Assert-Condition ($decoded -eq 4) "$($Definition.Type)에서 4개 프레임을 모두 복호화하지 못했습니다."
        Assert-Condition ($fullyDecoded -eq 4) "$($Definition.Type)에서 CRC까지 통과한 완전 복호 프레임이 4개가 아닙니다."
        Assert-Condition ($Session.rxResult.integrity.reconstructedLength -eq 464) "$($Definition.Type) 복원 크기가 464 byte가 아닙니다."
        Assert-Condition ($Session.rxResult.integrity.reconstructedRecords -eq 4) "$($Definition.Type) 복원 레코드가 4개가 아닙니다."
    }

    if ($Definition.Type -eq 'TEST_E_UDP_DROP') {
        Assert-Condition ($Session.rxResult.counters.simulatedDroppedDatagrams -gt 0) 'Test E에서 실제 Drop이 한 건도 발생하지 않았습니다.'
    }
    if ($Definition.Type -in @('TEST_B_RANDOM_ERRORS', 'TEST_C_BURST_ERRORS')) {
        Assert-Condition ($Session.rxResult.counters.injectedBitCount -eq 4) "$($Definition.Type) 주입 오류가 총 4 bit가 아닙니다."
    }
}

<#
판정값뿐 아니라 Sender/Receiver 6,000비트 프레임 증거가 실제로 병합됐는지 검증한다.
이 검사가 있어야 Agent 또는 서버 한쪽만 구버전으로 회귀한 상황을 즉시 발견할 수 있다.
#>
function Test-FrameEvidence {
    param(
        [object]$Session,
        [object]$Definition
    )

    # 서버 재기동 직후 Agent WebSocket 재연결 큐까지 고려해 최대 15초 동안 기다린다.
    $evidenceDeadline = (Get-Date).AddSeconds(15)
    do {
        # Invoke-RestMethod가 JSON 배열을 그대로 반환하므로 다시 @()로 감싸면 배열 하나로 오인할 수 있다.
        $evidence = Invoke-RestMethod -Uri "$BaseUrl/sessions/$($Session.sessionId)/frame-evidence"
        $evidenceComplete = $evidence.Count -eq 4 `
            -and -not ($evidence | Where-Object { -not $_.senderEvidenceAvailable }) `
            -and -not ($evidence | Where-Object { -not $_.receiverEvidenceAvailable })
        if (-not $evidenceComplete) {
            Start-Sleep -Milliseconds 200
        }
    } while (-not $evidenceComplete -and (Get-Date) -lt $evidenceDeadline)

    Assert-Condition ($evidence.Count -eq 4) "$($Definition.Type) 프레임 증거가 4개가 아닙니다."
    Assert-Condition (-not ($evidence | Where-Object { -not $_.senderEvidenceAvailable })) "$($Definition.Type) Sender 프레임 증거가 누락됐습니다."
    Assert-Condition (-not ($evidence | Where-Object { -not $_.receiverEvidenceAvailable })) "$($Definition.Type) Receiver 프레임 증거가 누락됐습니다."
    Assert-Condition (-not ($evidence | Where-Object { $_.transmittedToReceivedDifferences -ne 0 })) "$($Definition.Type) 송신/수신 프레임에 예상하지 않은 차이가 있습니다."

    $injected = ($evidence | Measure-Object referenceToTransmittedDifferences -Sum).Sum
    if ($Definition.Type -in @('TEST_B_RANDOM_ERRORS', 'TEST_C_BURST_ERRORS')) {
        Assert-Condition ($injected -eq 4) "$($Definition.Type) 점자 지도 오류 주입 합계가 4 bit가 아닙니다."
    } elseif ($Definition.Type -eq 'TEST_D_SYNC_RECOVERY') {
        Assert-Condition ($injected -eq 1) 'Test D 점자 지도 동기 손상이 1 bit가 아닙니다.'
        Assert-Condition (-not $evidence[0].decodeSucceeded) 'Test D 손상 동기 프레임이 복호화된 것으로 표시됩니다.'
        Assert-Condition ($null -eq $evidence[0].referenceToReencodedDifferences) 'Test D 제외 프레임에 재인코딩 비교값이 생겼습니다.'
        Assert-Condition $evidence[0].intentionalSyncRejection 'Test D 의도적 동기 제외 표시가 없습니다.'
    } else {
        Assert-Condition ($injected -eq 0) "$($Definition.Type)에 예상하지 않은 점자 지도 오류 주입이 있습니다."
    }

    $decodedEvidence = @($evidence | Where-Object { $_.decodeSucceeded })
    Assert-Condition (-not ($decodedEvidence | Where-Object { $_.referenceToReencodedDifferences -ne 0 })) "$($Definition.Type) 복호화 후 재인코딩 프레임이 기준과 다릅니다."

    $detailIndex = if ($Definition.Type -eq 'TEST_D_SYNC_RECOVERY') { 1 } else { 0 }
    $detail = Invoke-RestMethod -Uri "$BaseUrl/sessions/$($Session.sessionId)/frame-evidence/$detailIndex"
    foreach ($field in @('referenceFrame', 'transmittedFrame', 'receivedFrame', 'reencodedFrame')) {
        $bytes = [Convert]::FromBase64String($detail.$field)
        Assert-Condition ($bytes.Length -eq 750) "$($Definition.Type) $field 원문이 750 byte가 아닙니다."
    }
}

Assert-Condition (Test-Path -LiteralPath $SamplePath -PathType Leaf) "샘플 파일이 없습니다: $SamplePath"
Wait-AgentsReady
$uploadedInput = Upload-Sample
$allDefinitions = @(
    [pscustomobject]@{ Type = 'TEST_A_NORMAL'; ErrorCount = 1; DropRate = 0; RepeatCount = 3 },
    [pscustomobject]@{ Type = 'TEST_B_RANDOM_ERRORS'; ErrorCount = 1; DropRate = 0; RepeatCount = 3 },
    [pscustomobject]@{ Type = 'TEST_C_BURST_ERRORS'; ErrorCount = 1; DropRate = 0; RepeatCount = 3 },
    [pscustomobject]@{ Type = 'TEST_D_SYNC_RECOVERY'; ErrorCount = 1; DropRate = 0; RepeatCount = 3 },
    [pscustomobject]@{ Type = 'TEST_E_UDP_DROP'; ErrorCount = 1; DropRate = 30; RepeatCount = 5 }
)
$definitions = @($allDefinitions | Where-Object { $TestTypes -contains $_.Type })
Assert-Condition ($definitions.Count -gt 0) '실행할 유효한 TestTypes가 없습니다.'
$summary = foreach ($definition in $definitions) {
    Write-Host "[$($definition.Type)] 실행 중..."
    $session = Start-TestSession $uploadedInput $definition
    Test-SessionResult $session $definition
    Test-FrameEvidence $session $definition
    $integrity = $session.rxResult.integrity
    $row = [pscustomobject]@{
        Test = $definition.Type
        SessionId = $session.sessionId
        Verdict = $session.verdict
        Frames = "$($session.rxResult.counters.receivedLogicalFrames)/$($session.rxResult.counters.expectedLogicalFrames)"
        Decoded = Get-MetricValue $session.rxResult 'DecodedFrames'
        Records = "$($integrity.reconstructedRecords)/$($integrity.expectedRecords)"
        Bytes = "$($integrity.reconstructedLength)/$($integrity.sourceLength)"
        Sha256Match = $integrity.success
        PlannedDrop = $session.rxResult.counters.simulatedDroppedDatagrams
        InjectedBits = $session.rxResult.counters.injectedBitCount
        InvalidUdp = $session.rxResult.counters.invalidDatagrams
        DecodeFailed = $session.rxResult.counters.decodeFailedFrames
        SyncRejected = $session.rxResult.counters.syncRejectedFrames
    }
    Write-Host "[$($definition.Type)] PASS - session $($session.sessionId)"
    $row
}

Write-Host ''
Write-Host '선택한 sample-data 회귀시험이 모두 통과했습니다.'
$summary | Format-Table -AutoSize
