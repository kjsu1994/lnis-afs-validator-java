package kr.co.lnis.agent.gnss;

import com.fazecast.jSerialComm.SerialPort;
import kr.co.lnis.protocol.codec.GrawCodec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Windows COM 포트에서 GNSS 데이터를 읽어 canonical GRAW 청크로 변환한다.
 *
 * <p>전용 virtual thread에서 serial byte를 읽고 선택한 protocol parser에 전달한다. raw serial과 canonical
 * GRAW를 함께 계수하지만 서버에는 시험에 사용할 canonical 레코드만 별도 필드로 전달한다. stop 또는
 * close 시 포트를 닫고 가능한 경우 u-blox 임시 설정을 원래 값으로 복원한다.
 */
public final class SerialCaptureService implements AutoCloseable {
    public record Settings(String portName, int baudRate, String protocolId, String sessionName, String receiverModel,
                           String firmwareVersion, boolean dtrEnabled, boolean rtsEnabled) {}
    public record CaptureChunk(long index, byte[] rawSerial, byte[] canonical, long bytesRead, long records) {}
    private final AtomicBoolean running = new AtomicBoolean(); private volatile SerialPort port; private volatile Thread worker;
    private final List<byte[]> restoreCommands = new ArrayList<>();

    public List<String> portNames() { return Arrays.stream(SerialPort.getCommPorts()).map(SerialPort::getSystemPortName).sorted().toList(); }

    public synchronized void start(Settings settings, Consumer<CaptureChunk> chunks, Consumer<Throwable> failure) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Capture is already running");
        port = SerialPort.getCommPort(settings.portName); port.setBaudRate(settings.baudRate); port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);
        if (!port.openPort()) { running.set(false); throw new IllegalStateException("Unable to open " + settings.portName); }
        if (settings.dtrEnabled) port.setDTR(); else port.clearDTR();
        if (settings.rtsEnabled) port.setRTS(); else port.clearRTS();
        if ("ubx".equalsIgnoreCase(settings.protocolId)) configureUbloxTemporarily();
        worker = Thread.ofPlatform().name("lnis-gnss-capture").start(() -> run(settings, chunks, failure));
    }

    private void run(Settings settings, Consumer<CaptureChunk> chunks, Consumer<Throwable> failure) {
        UbloxParser ubx = new UbloxParser(); UUID testId = UUID.randomUUID(); long sequence = 0, bytesRead = 0, records = 0, chunkIndex = 0;
        ByteArrayOutputStream rawChunk = new ByteArrayOutputStream(1024 * 1024), canonicalChunk = new ByteArrayOutputStream(1024 * 1024);
        try {
            if (!"raw-only".equalsIgnoreCase(settings.protocolId)) {
                byte[] metadata = GrawCodec.encode(new GrawCodec.Envelope(testId, UUID.randomUUID(), sequence++, Instant.now(),
                        new GrawCodec.ReceiverMetadata(
                                settings.receiverModel,
                                settings.firmwareVersion,
                                settings.portName,
                                settings.baudRate,
                                settings.sessionName)));
                writeRecord(canonicalChunk, metadata); records++;
            }
            byte[] buffer = new byte[8192];
            while (running.get()) {
                int count = port.readBytes(buffer, buffer.length);
                if (count < 0) {
                    throw new IllegalStateException("Serial read failed");
                }
                if (count == 0) {
                    continue;
                }
                rawChunk.write(buffer, 0, count); bytesRead += count;
                if ("ubx".equalsIgnoreCase(settings.protocolId)) for (var frame : ubx.push(buffer, count)) {
                    var message = UbloxParser.toCanonical(frame);
                    if (message != null) {
                        writeRecord(
                                canonicalChunk,
                                GrawCodec.encode(new GrawCodec.Envelope(
                                        testId,
                                        UUID.randomUUID(),
                                        sequence++,
                                        Instant.now(),
                                        message)));
                        records++;
                    }
                } else if ("lnis-canonical-v1".equalsIgnoreCase(settings.protocolId)) canonicalChunk.write(buffer, 0, count);
                if (rawChunk.size() >= 1024 * 1024 || canonicalChunk.size() >= 1024 * 1024)
                    chunks.accept(new CaptureChunk(chunkIndex++, drain(rawChunk), drain(canonicalChunk), bytesRead, records));
            }
            if (rawChunk.size() > 0 || canonicalChunk.size() > 0) {
                chunks.accept(new CaptureChunk(
                        chunkIndex,
                        drain(rawChunk),
                        drain(canonicalChunk),
                        bytesRead,
                        records));
            }
        } catch (Throwable error) { failure.accept(error); }
        finally { restoreUbloxConfiguration(); if (port != null) port.closePort(); running.set(false); }
    }
    private static void writeRecord(ByteArrayOutputStream out, byte[] record) {
        out.writeBytes(ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(record.length)
                .array());
        out.writeBytes(record);
    }
    private static byte[] drain(ByteArrayOutputStream out) { byte[] bytes = out.toByteArray(); out.reset(); return bytes; }
    public synchronized void stop() { running.set(false); }
    private void configureUbloxTemporarily() {
        restoreCommands.clear(); write(UbloxParser.command(0x0A,0x04,new byte[0]));
        for (int[] message : List.of(new int[]{0x02,0x15},new int[]{0x02,0x13})) {
            byte[] original=pollMessageRate(message[0],message[1]);
            if (original != null) {
                restoreCommands.add(UbloxParser.command(0x06, 0x01, original));
                byte[] enabled = original.clone();
                if (enabled.length >= 8) {
                    enabled[3] = 1;
                    enabled[5] = 1;
                } else if (enabled.length >= 3) {
                    enabled[2] = 1;
                }
                write(UbloxParser.command(0x06, 0x01, enabled));
            }
            else write(UbloxParser.command(0x06,0x01,new byte[]{(byte)message[0],(byte)message[1],1}));
        }
    }
    private byte[] pollMessageRate(int messageClass,int messageId) {
        write(UbloxParser.command(
                0x06, 0x01, new byte[]{(byte) messageClass, (byte) messageId}));
        UbloxParser parser = new UbloxParser();
        long deadline = System.nanoTime() + 1_500_000_000L;
        byte[] buffer = new byte[2048];
        while (System.nanoTime() < deadline) {
            int count = port.readBytes(buffer, buffer.length);
            if (count <= 0) {
                continue;
            }
            for (var frame : parser.push(buffer, count)) {
                if (frame.messageClass() == 0x06
                        && frame.messageId() == 0x01
                        && frame.payload().length >= 3
                        && (frame.payload()[0] & 0xff) == messageClass
                        && (frame.payload()[1] & 0xff) == messageId) {
                    return frame.payload();
                }
            }
        }
        return null;
    }
    private void restoreUbloxConfiguration(){if(port==null||!port.isOpen())return;for(byte[] command:restoreCommands)write(command);restoreCommands.clear();}
    private void write(byte[] value) {
        if (port.writeBytes(value, value.length) != value.length) {
            throw new IllegalStateException("Unable to configure u-blox receiver");
        }
    }
    @Override public void close() { stop(); }
}
