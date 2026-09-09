package server.shared.model;

import server.shared.model.AgentProtocol.Envelope;

/** 전송 방식과 시험 제어를 분리한다. 내부 실행기는 소켓 없이 이 계약으로 호출한다. */
public interface CommandEndpoint {
    boolean online();

    /** 명령을 전달할 수 없거나 실행기가 거부하면 예외를 반환한다. */
    void send(Envelope message);
}
