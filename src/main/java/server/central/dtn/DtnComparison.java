package server.central.dtn;

import server.shared.model.DtnModels.Pvt;

import java.util.*;

/** 관측 시각과 유효성을 검사한 다음 동일 epoch의 PVT 차이를 비교한다. */
public final class DtnComparison {
    private DtnComparison()
    {}

    public static Map<String, Object> compare(List<Pvt> reference, List<Pvt> received)
    {
        if (reference == null || received == null || reference.size() != received.size()) {
            throw new IllegalArgumentException("PVT 관측 개수 불일치");
        }
        List<Map<String, Object>> epochs = new ArrayList<>();
        int comparable = 0, velocityComparable = 0;
        boolean matches = true;
        for (int i = 0; i < reference.size(); i++) {
            Pvt a = reference.get(i), b = received.get(i);
            if (a.getWeek() != b.getWeek()
                    || Double.compare(a.getTowSeconds(), b.getTowSeconds()) != 0) {
                throw new IllegalArgumentException("PVT 관측 시각 또는 순서 불일치");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", a.getWeek());
            row.put("towSeconds", a.getTowSeconds());
            row.put("referenceValid", a.isPositionValid());
            row.put("receivedValid", b.isPositionValid());
            if (a.isPositionValid() != b.isPositionValid()
                    || a.isVelocityValid() != b.isVelocityValid()) {
                matches = false;
            }
            if (a.isPositionValid() && b.isPositionValid()) {
                comparable++;
                double position = distance(a.getEcefMeters(), b.getEcefMeters());
                if (a.getReceiverClockBiasSeconds() == null
                        || b.getReceiverClockBiasSeconds() == null) {
                    throw new IllegalArgumentException("시계 오차 누락");
                }
                double clock =
                        Math.abs(a.getReceiverClockBiasSeconds() - b.getReceiverClockBiasSeconds());
                if (!Double.isFinite(clock)) {
                    throw new IllegalArgumentException("시계 오차 범위 오류");
                }
                row.put("positionDifferenceMeters", position);
                row.put("clockDifferenceSeconds", clock);
                matches &= position <= 0.001 && clock <= 1e-9;
                if (a.isVelocityValid() && b.isVelocityValid()) {
                    velocityComparable++;
                    double velocity =
                            distance(
                                    a.getVelocityMetersPerSecond(), b.getVelocityMetersPerSecond());
                    row.put("velocityDifferenceMetersPerSecond", velocity);
                    matches &= velocity <= 0.001;
                }
            }
            epochs.add(row);
        }
        return Map.of(
                "comparableEpochs",
                comparable,
                "velocityComparableEpochs",
                velocityComparable,
                "epochs",
                epochs,
                "verdict",
                !matches
                        ? "FAIL"
                        : comparable == 0 || velocityComparable == 0 ? "INCONCLUSIVE" : "PASS",
                "positionToleranceMeters",
                0.001,
                "velocityToleranceMetersPerSecond",
                0.001,
                "clockToleranceSeconds",
                1e-9);
    }

    private static double distance(double[] a, double[] b)
    {
        if (a == null || b == null || a.length != 3 || b.length != 3) {
            throw new IllegalArgumentException("PVT 벡터 길이 오류");
        }
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])) {
                throw new IllegalArgumentException("PVT 값 오류");
            }
            sum += Math.pow(a[i] - b[i], 2);
        }
        return Math.sqrt(sum);
    }
}

