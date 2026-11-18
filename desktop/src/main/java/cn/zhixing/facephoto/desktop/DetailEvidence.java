package cn.zhixing.facephoto.desktop;

import cn.zhixing.facephoto.core.BatchModels.PhotoResult;
import cn.zhixing.facephoto.core.BatchModels.Snapshot;
import cn.zhixing.facephoto.core.QualityRules;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class DetailEvidence {
    private static final String[] PART_NAMES = {"左眼", "右眼", "鼻", "口", "左颊", "右颊", "下巴"};
    private static final String[] PART_KEYS =
            {"leftEye", "rightEye", "nose", "mouth", "leftCheek", "rightCheek", "chin"};

    private DetailEvidence() {}

    static void render(Snapshot snapshot, PhotoResult photo, Label dimensions, Label title,
                       Label reasons, GridPane metrics, TitledPane capturePane, Label capture,
                       Label review) {
        metrics.getChildren().clear();
        title.getStyleClass().removeIf(style -> style.startsWith("status-"));
        capturePane.setVisible(photo != null);
        capturePane.setManaged(photo != null);
        if (photo == null) {
            dimensions.setText("");
            title.setText("");
            reasons.setText("");
            capture.setText("");
            review.setText("未复核");
            return;
        }
        title.setText(photo.status().label());
        title.getStyleClass().add("status-" + photo.status().name().toLowerCase(java.util.Locale.ROOT));
        reasons.setText(MainController.reasonSummary(photo));
        review.setText(PhotoDialogs.reviewLabel(photo.reviewStatus())
                + (MainController.text(photo.reviewNote()).isBlank() ? "" : " · " + photo.reviewNote()));
        JsonNode value = photo.measurement();
        dimensions.setText(value == null ? "" : dimensions(value.path("image")));
        capture.setText(captureMetadata(snapshot, photo));
        if (value != null) addMetrics(snapshot, value, metrics);
    }

    private static void addMetrics(Snapshot snapshot, JsonNode value, GridPane metrics) {
        var rules = snapshot.rules();
        addRow(metrics, 0, "检测项", "测量值", "阈值");
        metrics.getChildren().forEach(node -> node.getStyleClass().add("metric-heading"));
        int faces = value.path("faceCount").asInt(-1);
        addRow(metrics, 1, "人脸数量", number(value.path("faceCount")), "= 1");
        if (faces != 1) {
            addRow(metrics, 2, "质量检测", "未执行", "—");
            return;
        }
        var box = value.path("boxes").path(0);
        addRow(metrics, 2, "人脸宽度", number(box.path("width")), "≥ " + rules.faceWidth() + " px");
        addRow(metrics, 3, "人脸高度", number(box.path("height")), "≥ " + rules.faceHeight() + " px");
        addRow(metrics, 4, "画面边距", number(QualityRules.margin(value)), "> " + number(rules.edgeMargin()) + " px");
        var pose = value.path("pose");
        addRow(metrics, 5, "左右偏转", number(pose.path("yaw")), "|角度| ≤ " + rules.poseLimit() + "°");
        addRow(metrics, 6, "俯仰", number(pose.path("pitch")), "|角度| ≤ " + rules.poseLimit() + "°");
        addRow(metrics, 7, "倾斜", number(pose.path("roll")), "|角度| ≤ " + rules.poseLimit() + "°");
        addRow(metrics, 8, "模糊度", number(value.path("blur")), "≤ " + number(rules.blurLimit()));
        addRow(metrics, 9, "局部遮挡", highestPart(value.path("occlusion")), "≤ " + number(rules.occlusionLimit()));
        addRow(metrics, 10, "光照", number(value.path("illumination")),
                rules.lightEnabled() ? "≥ " + number(rules.lightLimit()) : "未启用");
    }

    private static void addRow(GridPane grid, int row, String name, String value, String limit) {
        grid.addRow(row, new Label(name), new Label(value), new Label(limit));
    }

    private static String captureMetadata(Snapshot snapshot, PhotoResult photo) {
        var text = new ArrayList<String>();
        text.add(photo.relativePath());
        text.add(MainController.text(photo.date()) + " " + MainController.text(photo.time()));
        text.add("抓拍状态  " + missing(photo.captureStatus()));
        text.add("rec  " + missing(photo.rec()) + "   live  " + missing(photo.live()));
        if (snapshot != null) text.add("规则版本  " + snapshot.rules().version());
        if (photo.engineId() != null) text.add("引擎  " + photo.engineId() + " " + missing(photo.engineVersion()));
        if (photo.failureCode() != null) {
            text.add("故障  " + missing(photo.failureStage()) + " · " + photo.failureCode());
        }
        if (photo.measurement() != null) appendQuality(text, photo.measurement());
        return String.join("\n", text);
    }

    private static void appendQuality(List<String> lines, JsonNode measurement) {
        JsonNode image = measurement.path("image");
        if (image.path("orientation").isNumber()) {
            lines.add("原始尺寸  " + number(image.path("sourceWidth")) + " × " + number(image.path("sourceHeight"))
                    + "   EXIF " + image.path("orientation").asInt());
        }
        JsonNode occlusion = measurement.path("occlusion");
        if (occlusion.isObject()) {
            for (int i = 0; i < PART_KEYS.length; i++) {
                lines.add(PART_NAMES[i] + "遮挡  " + number(occlusion.path(PART_KEYS[i])));
            }
        }
        for (JsonNode item : measurement.path("missing")) {
            lines.add("缺测  " + item.path("field").asText() + " · " + item.path("reason").asText());
        }
    }

    private static String highestPart(JsonNode occlusion) {
        double highest = Double.NEGATIVE_INFINITY;
        String part = "";
        for (int i = 0; i < PART_KEYS.length; i++) {
            double score = occlusion.path(PART_KEYS[i]).asDouble(Double.NaN);
            if (score > highest) {
                highest = score;
                part = PART_NAMES[i];
            }
        }
        return part.isEmpty() ? "—" : part + " " + number(highest);
    }

    private static String dimensions(JsonNode image) {
        return image.path("width").isNumber()
                ? image.path("width").asInt() + " × " + image.path("height").asInt() : "";
    }

    private static String number(JsonNode node) {
        return node.isNumber() ? number(node.asDouble()) : "—";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).round(
                new java.math.MathContext(8)).stripTrailingZeros().toPlainString() : "—";
    }

    private static String missing(Object value) {
        return value == null ? "—" : value.toString();
    }
}
