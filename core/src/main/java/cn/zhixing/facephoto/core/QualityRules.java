package cn.zhixing.facephoto.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.zhixing.facephoto.core.BatchModels.*;

public final class QualityRules {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("FACE_NONE", "未检测到人脸"),
            Map.entry("FACE_MULTIPLE", "检测到多张人脸"),
            Map.entry("FACE_TOO_SMALL", "人脸尺寸不足"),
            Map.entry("FACE_OUTSIDE", "人脸出画"),
            Map.entry("FACE_EDGE", "人脸贴边"),
            Map.entry("POSE_LIMIT", "人脸姿态超限"),
            Map.entry("BLUR_LIMIT", "照片模糊"),
            Map.entry("OCCLUSION_LIMIT", "局部遮挡"),
            Map.entry("LIGHT_LOW", "面部偏暗"),
            Map.entry("METRIC_MISSING", "测量项缺失"),
            Map.entry("METRIC_BORDERLINE", "测量值临界"),
            Map.entry("DETECTION_ERROR", "检测失败"));
    private static final List<String> AXES = List.of("yaw", "pitch", "roll");
    private static final List<String> OCCLUSION_PARTS =
            List.of("leftEye", "rightEye", "nose", "mouth", "leftCheek", "rightCheek", "chin");

    private QualityRules() {
    }

    public record Decision(Status status, List<Reason> reasons) {
    }

    public static String label(String code) {
        return LABELS.getOrDefault(code, code);
    }

    public static Decision failure(JsonNode diagnostic) {
        return new Decision(Status.ERROR, List.of(new Reason("DETECTION_ERROR", label("DETECTION_ERROR"),
                JSON.arrayNode().add(diagnostic))));
    }

    public static Decision evaluate(JsonNode data, RuleSettings settings) {
        Evaluation evaluation = new Evaluation(settings);
        int faceCount = data.path("faceCount").asInt(-1);
        if (faceCount < 0) {
            evaluation.missing("faceCount");
        } else if (faceCount != 1) {
            evaluation.add(faceCount == 0 ? "FACE_NONE" : "FACE_MULTIPLE",
                    evidence("faceCount", faceCount, "=", 1), true);
        } else {
            dimensions(data, evaluation);
            axialPose(data, evaluation);
            evaluation.metric("blur", data.path("blur"), "BLUR_LIMIT", false, settings.blurLimit(), true);
            for (String part : OCCLUSION_PARTS) {
                evaluation.metric("occlusion." + part, data.path("occlusion").path(part),
                        "OCCLUSION_LIMIT", false, settings.occlusionLimit(), true);
            }
            if (settings.lightEnabled()) {
                evaluation.metric("illumination", data.path("illumination"),
                        "LIGHT_LOW", true, settings.lightLimit(), true);
            }
        }
        return evaluation.decision();
    }

    private static void dimensions(JsonNode data, Evaluation evaluation) {
        RuleSettings settings = evaluation.settings;
        JsonNode box = data.path("boxes").path(0);
        double width = positive(box.path("width"));
        double height = positive(box.path("height"));
        evaluation.limit("faceWidth", width, width, "FACE_TOO_SMALL", true, settings.faceWidth());
        evaluation.limit("faceHeight", height, height, "FACE_TOO_SMALL", true, settings.faceHeight());
        double margin = margin(data);
        if (!Double.isFinite(margin)) {
            evaluation.missing("completeness");
        } else if (margin < 0 && settings.outsideEvidence()) {
            evaluation.add("FACE_OUTSIDE", evidence("margin", margin, ">=", 0), true);
        } else if (margin <= settings.edgeMargin()) {
            evaluation.add("FACE_EDGE", evidence("margin", margin, ">", settings.edgeMargin()), false);
        } else {
            evaluation.checkBand("margin", margin);
        }
    }

    public static double margin(JsonNode data) {
        JsonNode box = data.path("boxes").path(0);
        JsonNode image = data.path("image");
        double x = value(box.path("x"));
        double y = value(box.path("y"));
        return Math.min(Math.min(x, y), Math.min(
                positive(image.path("width")) - x - positive(box.path("width")),
                positive(image.path("height")) - y - positive(box.path("height"))));
    }

    private static void axialPose(JsonNode data, Evaluation evaluation) {
        for (String axis : AXES) {
            double value = value(data.path("pose").path(axis));
            if (Math.abs(value) > 180) {
                value = Double.NaN;
            }
            evaluation.limit(axis, value, Math.abs(value), "POSE_LIMIT", false, evaluation.settings.poseLimit());
        }
    }

    private static double value(JsonNode node) {
        return node.isNumber() && Double.isFinite(node.doubleValue()) ? node.doubleValue() : Double.NaN;
    }

    private static double positive(JsonNode node) {
        double number = value(node);
        return number > 0 ? number : Double.NaN;
    }

    private static ObjectNode evidence(String field, double observed, String comparison, double threshold) {
        return JSON.objectNode().put("field", field).put("value", observed)
                .put("comparison", comparison).put("threshold", threshold);
    }

    private static final class Evaluation {
        private final RuleSettings settings;
        private final Map<String, ArrayNode> reasons = new LinkedHashMap<>();
        private boolean failed;

        private Evaluation(RuleSettings settings) {
            this.settings = settings;
        }

        private void add(String code, ObjectNode evidence, boolean failure) {
            reasons.computeIfAbsent(code, ignored -> JSON.arrayNode()).add(evidence);
            failed |= failure;
        }

        private void missing(String field) {
            add("METRIC_MISSING", JSON.objectNode().put("field", field), false);
        }

        private void metric(String field, JsonNode node, String code, boolean minimum, double threshold,
                            boolean unitInterval) {
            double number = value(node);
            if (unitInterval && (number < 0 || number > 1)) {
                number = Double.NaN;
            }
            limit(field, number, number, code, minimum, threshold);
        }

        private void limit(String field, double value, double actual, String code, boolean minimum, double threshold) {
            if (!Double.isFinite(actual)) {
                missing(field);
            } else if (minimum ? actual < threshold : actual > threshold) {
                ObjectNode evidence = evidence(field, value, minimum ? ">=" : "<=", threshold);
                if (field.equals("yaw") || field.equals("pitch") || field.equals("roll")) {
                    evidence.put("absoluteValue", actual);
                }
                add(code, evidence, true);
            } else {
                checkBand(field, actual);
            }
        }

        private void checkBand(String field, double value) {
            for (CriticalBand band : settings.criticalBands()) {
                if (field.equals(band.metric()) && band.contains(value)) {
                    add("METRIC_BORDERLINE", JSON.objectNode().put("field", field).put("value", value)
                            .put("lower", band.lower()).put("upper", band.upper())
                            .put("includesLower", band.includesLower()).put("includesUpper", band.includesUpper()),
                            false);
                }
            }
        }

        private Decision decision() {
            Status status = failed ? Status.FAIL : reasons.isEmpty() ? Status.PASS : Status.REVIEW;
            return new Decision(status, reasons.entrySet().stream()
                    .map(entry -> new Reason(entry.getKey(), label(entry.getKey()), entry.getValue())).toList());
        }
    }
}
