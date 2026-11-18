package cn.zhixing.facephoto.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static cn.zhixing.facephoto.core.BatchModels.*;

final class CsvExporter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String[] DETAIL_COLUMNS = {
            "相对路径", "抓拍日期", "抓拍时间", "原始宽度", "原始高度", "归一化宽度", "归一化高度", "EXIF方向",
            "原始状态", "rec", "live", "自动状态", "原因及证据", "人脸数量", "人脸框",
            "左右偏转", "俯仰", "倾斜", "模糊度", "左眼遮挡", "右眼遮挡", "鼻遮挡", "嘴遮挡",
            "左颊遮挡", "右颊遮挡", "下巴遮挡", "光照", "最小边距", "缺失项",
            "规则版本", "规则阈值", "引擎标识", "引擎版本", "测量配置指纹", "失败阶段", "失败代码",
            "人工结论", "人工备注"
    };

    private CsvExporter() {
    }

    static ExportResult write(Snapshot snapshot, ExportKind kind, Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getFileName() == null || !absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IOException("导出目标必须使用 .csv 扩展名");
        }
        Path temp = Files.createTempFile(absolute.getParent(), ".face-photo-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF');
                switch (kind) {
                    case DETAIL -> detail(writer, snapshot);
                    case DATE -> byDate(writer, snapshot.rows());
                    case DATE_REASON -> byDateReason(writer, snapshot.rows());
                }
            }
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return new ExportResult(absolute, snapshot.rows().size());
    }

    private static void detail(BufferedWriter writer, Snapshot snapshot) throws IOException {
        line(writer, (Object[]) DETAIL_COLUMNS);
        String thresholds = JSON.writeValueAsString(snapshot.rules());
        for (PhotoResult row : snapshot.rows()) {
            detailRow(writer, row, snapshot.rules().version(), thresholds);
        }
    }

    private static void detailRow(BufferedWriter writer, PhotoResult row, String version, String thresholds)
            throws IOException {
        JsonNode data = row.measurement();
        JsonNode image = data.path("image");
        JsonNode pose = data.path("pose");
        JsonNode occlusion = data.path("occlusion");
        double margin = data.path("faceCount").asInt() == 1 ? QualityRules.margin(data) : Double.NaN;
        line(writer, row.relativePath(), row.date(), row.time(),
                image.path("sourceWidth"), image.path("sourceHeight"), image.path("width"), image.path("height"),
                image.path("orientation"), row.captureStatus(), row.rec(), row.live(), row.status().label(),
                JSON.writeValueAsString(row.reasons()), data.path("faceCount"), data.path("boxes"),
                pose.path("yaw"), pose.path("pitch"), pose.path("roll"), data.path("blur"), occlusion.path("leftEye"),
                occlusion.path("rightEye"), occlusion.path("nose"), occlusion.path("mouth"), occlusion.path("leftCheek"),
                occlusion.path("rightCheek"), occlusion.path("chin"), data.path("illumination"),
                Double.isFinite(margin) ? margin : null, data.path("missing"), version, thresholds,
                row.engineId(), row.engineVersion(), row.profileFingerprint(), row.failureStage(), row.failureCode(),
                reviewLabel(row.reviewStatus()), row.reviewNote());
    }

    private static String reviewLabel(ReviewStatus status) {
        return switch (status) {
            case UNREVIEWED -> null;
            case PASS -> "合格";
            case FAIL -> "不合格";
            case REVIEW -> "待复核";
        };
    }

    private static void byDate(BufferedWriter writer, List<PhotoResult> rows) throws IOException {
        line(writer, "日期", "已检测", "合格", "不合格", "待复核", "检测失败");
        Map<String, Map<Status, Integer>> counts = new LinkedHashMap<>();
        for (PhotoResult row : rows) {
            counts.computeIfAbsent(date(row), ignored -> new EnumMap<>(Status.class))
                    .merge(row.status(), 1, Integer::sum);
        }
        for (var entry : counts.entrySet()) {
            Map<Status, Integer> values = entry.getValue();
            int total = values.values().stream().mapToInt(Integer::intValue).sum();
            line(writer, entry.getKey(), total, values.getOrDefault(Status.PASS, 0),
                    values.getOrDefault(Status.FAIL, 0), values.getOrDefault(Status.REVIEW, 0),
                    values.getOrDefault(Status.ERROR, 0));
        }
    }

    private static void byDateReason(BufferedWriter writer, List<PhotoResult> rows) throws IOException {
        line(writer, "日期", "原因", "命中张数");
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (PhotoResult row : rows) {
            var values = counts.computeIfAbsent(date(row), ignored -> new LinkedHashMap<>());
            row.reasons().stream().map(Reason::code).distinct()
                    .forEach(code -> values.merge(code, 1, Integer::sum));
        }
        for (var day : counts.entrySet()) {
            if (day.getValue().isEmpty()) {
                line(writer, day.getKey(), "无异常原因", 0);
            }
            var ordered = day.getValue().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(entry -> QualityRules.label(entry.getKey()), Comparator.naturalOrder()))
                    .toList();
            for (var item : ordered) {
                line(writer, day.getKey(), QualityRules.label(item.getKey()), item.getValue());
            }
        }
    }

    private static String date(PhotoResult row) {
        return row.date() == null ? "无日期" : row.date();
    }

    private static void line(BufferedWriter writer, Object... fields) throws IOException {
        writer.write(String.join(",", Arrays.stream(fields).map(CsvExporter::cell).toList()));
        writer.write("\r\n");
    }

    private static String cell(Object field) {
        if (field == null || field instanceof JsonNode node && (node.isNull() || node.isMissingNode())) {
            return "";
        }
        if (field instanceof Number || field instanceof JsonNode node && node.isNumber()) {
            return field.toString();
        }
        String value = field instanceof JsonNode node && node.isTextual() ? node.textValue() : field.toString();
        String stripped = value.stripLeading();
        if (!value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0
                || !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
