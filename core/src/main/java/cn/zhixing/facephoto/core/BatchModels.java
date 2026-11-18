package cn.zhixing.facephoto.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class BatchModels {
    private BatchModels() {
    }

    public enum BatchState { READY, CHECKING, RUNNING, CANCELLING, CANCELLED, PAUSED, COMPLETE }

    public enum Status {
        PASS("合格"), FAIL("不合格"), REVIEW("待复核"), ERROR("检测失败");

        private final String label;

        Status(String label) { this.label = label; }

        public String label() { return label; }
    }

    public enum ReviewStatus { UNREVIEWED, PASS, FAIL, REVIEW }
    public enum ExportScope { FILTERED, ALL }
    public enum ExportKind { DETAIL, DATE, DATE_REASON }

    public record Batch(long id, Path root, BatchState state, int total, int committed,
                        int unreadableDirectories, String errorCode) {
    }

    public record Filter(String date, Status status, String reason, String keyword) {
        public static final Filter ALL = new Filter(null, null, null, null);
    }

    public record Snapshot(Batch batch, RuleSettings rules, Map<String, Integer> dates,
                           Map<Status, Integer> statusCounts, Map<String, Integer> reasonCounts,
                           List<PhotoResult> rows, int filteredCount) {
    }

    public record PhotoResult(long itemId, Long revisionId, String relativePath, String fileName,
                              String date, String time, String captureStatus, Integer rec, Integer live,
                              Status status, List<Reason> reasons, JsonNode measurement, String sha256,
                              String failureStage, String failureCode, ReviewStatus reviewStatus,
                              String reviewNote, String engineId, String engineVersion,
                              String profileFingerprint) {
    }

    public record Reason(String code, String label, JsonNode evidence) {
    }

    public record CriticalBand(String metric, double lower, double upper,
                               boolean includesLower, boolean includesUpper) {
        public CriticalBand {
            if (metric == null || metric.isBlank() || !Double.isFinite(lower)
                    || !Double.isFinite(upper) || upper < lower) {
                throw new IllegalArgumentException("临界区间无效");
            }
        }

        public boolean contains(double value) {
            return (value > lower || includesLower && value == lower)
                    && (value < upper || includesUpper && value == upper);
        }
    }

    public record RuleSettings(String version, int faceWidth, int faceHeight, int poseLimit,
                               double blurLimit, double occlusionLimit, double lightLimit,
                               boolean lightEnabled, double edgeMargin, boolean outsideEvidence,
                               List<CriticalBand> criticalBands) {
        public RuleSettings {
            if (faceWidth < 1 || faceHeight < 1 || poseLimit < 0 || poseLimit > 180
                    || !score(blurLimit) || !score(occlusionLimit) || !score(lightLimit)
                    || !Double.isFinite(edgeMargin) || edgeMargin < 0) {
                throw new IllegalArgumentException("规则阈值无效");
            }
            criticalBands = criticalBands == null ? List.of() : List.copyOf(criticalBands);
        }

        public RuleSettings(String version, int faceWidth, int faceHeight, int poseLimit,
                            double blurLimit, double occlusionLimit, double lightLimit,
                            boolean lightEnabled, double edgeMargin, boolean outsideEvidence) {
            this(version, faceWidth, faceHeight, poseLimit, blurLimit, occlusionLimit, lightLimit,
                    lightEnabled, edgeMargin, outsideEvidence, List.of());
        }

        public static RuleSettings defaults() {
            return new RuleSettings("trial-1", 100, 100, 15, 0.60, 0.60, 0.60, false, 0, false);
        }

        public RuleSettings withVersion(String nextVersion) {
            return new RuleSettings(nextVersion, faceWidth, faceHeight, poseLimit, blurLimit,
                    occlusionLimit, lightLimit, lightEnabled, edgeMargin, outsideEvidence, criticalBands);
        }

        private static boolean score(double value) {
            return value >= 0 && value <= 1;
        }
    }

    public record ExportResult(Path file, int count) {
    }
}
