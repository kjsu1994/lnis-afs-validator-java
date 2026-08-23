package kr.co.lnis.agent.session.afs;

import kr.co.lnis.common.codec.Hashing;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** 순서가 뒤섞이거나 중복된 AFS fragment를 원래 GRAW 레코드로 복원한다. */
public final class AfsReassembler {
    private final SortedMap<Long, RecordState> records = new TreeMap<>();
    public void add(AfsRawFragmentCodec.Fragment fragment) {
        records.computeIfAbsent(
                        fragment.recordSequence(),
                        ignored -> new RecordState(
                                fragment.fragmentCount(),
                                fragment.recordLength(),
                                fragment.recordCrc32()))
                .add(fragment);
    }
    public List<byte[]> completeRecords() {
        List<byte[]> result = new ArrayList<>();
        records.values().forEach(record -> {
            byte[] value = record.build();
            if (value != null) {
                result.add(value);
            }
        });
        return result;
    }
    public long incompleteCount() { return records.values().stream().filter(x -> !x.complete()).count(); }
    private static final class RecordState {
        final byte[][] fragments; final long length, crc;
        RecordState(int count, long length, long crc) { this.fragments = new byte[count][]; this.length = length; this.crc = crc; }
        void add(AfsRawFragmentCodec.Fragment value) {
            if (value.fragmentCount() != fragments.length
                    || value.recordLength() != length
                    || value.recordCrc32() != crc) {
                throw new IllegalArgumentException("Conflicting AFS fragment metadata");
            }
            byte[] old = fragments[value.fragmentIndex()];
            if (old != null && !Arrays.equals(old, value.payload())) {
                throw new IllegalArgumentException("Conflicting duplicate fragment");
            }
            fragments[value.fragmentIndex()] = value.payload();
        }
        boolean complete() { return Arrays.stream(fragments).allMatch(Objects::nonNull); }
        byte[] build() {
            if (!complete()) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Arrays.stream(fragments).forEach(out::writeBytes);
            byte[] result = out.toByteArray();
            if (result.length != length || Hashing.crc32(result) != crc) {
                throw new IllegalArgumentException("Reassembled GRAW integrity failure");
            }
            return result;
        }
    }
}
