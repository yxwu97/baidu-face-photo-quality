package cn.zhixing.facephoto.desktop;

import cn.zhixing.facephoto.core.BatchModels.ExportKind;
import cn.zhixing.facephoto.core.BatchModels.ExportScope;
import cn.zhixing.facephoto.core.BatchModels.PhotoResult;
import cn.zhixing.facephoto.core.BatchModels.ReviewStatus;
import cn.zhixing.facephoto.core.BatchModels.RuleSettings;
import cn.zhixing.facephoto.core.QualityRules;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class PhotoDialogs {
    private static final String[] THRESHOLDS =
            {"人脸宽度（≥ px）", "人脸高度（≥ px）", "姿态角绝对值（≤ °）",
             "模糊度（≤）", "各部位遮挡（≤）", "光照（≥）"};

    record Review(ReviewStatus status, String note) {}
    record Export(ExportScope scope, ExportKind kind) {}

    private PhotoDialogs() {}

    static Optional<RuleSettings> settings(Window owner, RuleSettings current) {
        ButtonType apply = new ButtonType("应用并重新判定", ButtonBar.ButtonData.OK_DONE);
        Dialog<RuleSettings> dialog = dialog(owner, "检测设置", apply);
        TextField[] fields = new TextField[THRESHOLDS.length];
        CheckBox light = new CheckBox("启用光照阈值");
        GridPane thresholds = new GridPane(10, 8);
        for (int i = 0; i < fields.length; i++) {
            fields[i] = new TextField();
            fields[i].setPrefColumnCount(8);
            Label label = new Label(THRESHOLDS[i]);
            label.setLabelFor(fields[i]);
            thresholds.addRow(i, label, fields[i]);
        }
        fill(fields, light, current);
        Button defaults = new Button("恢复默认值");
        Label validation = new Label();
        validation.getStyleClass().add("status-fail");
        RuleSettings[] basis = {current};
        defaults.setOnAction(e -> {
            basis[0] = RuleSettings.defaults();
            fill(fields, light, basis[0]);
            validation.setText("");
        });
        VBox form = new VBox(10, new Label("当前规则  " + current.version()), thresholds,
                light, defaults, validation);
        content(dialog, form);
        dialog.getDialogPane().lookupButton(apply).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                readRules(fields, light, basis[0]);
            } catch (IllegalArgumentException e) {
                validation.setText("请检查检测阈值。尺寸和角度须为整数，分值须为 0 至 1。");
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == apply ? readRules(fields, light, basis[0]) : null);
        return dialog.showAndWait();
    }

    private static void fill(TextField[] fields, CheckBox light, RuleSettings rules) {
        fields[0].setText(Integer.toString(rules.faceWidth()));
        fields[1].setText(Integer.toString(rules.faceHeight()));
        fields[2].setText(Integer.toString(rules.poseLimit()));
        fields[3].setText(String.format(Locale.ROOT, "%.2f", rules.blurLimit()));
        fields[4].setText(String.format(Locale.ROOT, "%.2f", rules.occlusionLimit()));
        fields[5].setText(String.format(Locale.ROOT, "%.2f", rules.lightLimit()));
        light.setSelected(rules.lightEnabled());
    }

    private static RuleSettings readRules(TextField[] fields, CheckBox light, RuleSettings base) {
        return new RuleSettings(base.version(), integer(fields[0]), integer(fields[1]),
                integer(fields[2]), decimal(fields[3]), decimal(fields[4]), decimal(fields[5]),
                light.isSelected(), base.edgeMargin(), base.outsideEvidence(), base.criticalBands());
    }

    private static int integer(TextField field) {
        return Integer.parseInt(field.getText().strip());
    }

    private static double decimal(TextField field) {
        String value = field.getText().strip();
        if (!value.matches("[01](\\.\\d{1,2})?|\\.\\d{1,2}")) throw new IllegalArgumentException();
        return Double.parseDouble(value);
    }

    static Optional<Review> review(Window owner, PhotoResult photo) {
        ButtonType save = new ButtonType("保存复核", ButtonBar.ButtonData.OK_DONE);
        Dialog<Review> dialog = dialog(owner, "人工复核", save);
        ComboBox<String> status = new ComboBox<>();
        for (ReviewStatus value : ReviewStatus.values()) status.getItems().add(reviewLabel(value));
        status.getSelectionModel().select(photo.reviewStatus().ordinal());
        TextArea note = new TextArea(photo.reviewNote() == null ? "" : photo.reviewNote());
        note.setWrapText(true);
        note.setPrefRowCount(4);
        Label count = new Label();
        Runnable update = () -> {
            String text = note.getText();
            int length = text.codePointCount(0, text.length());
            count.setText(length + " / 300 字");
            dialog.getDialogPane().lookupButton(save).setDisable(length > 300);
        };
        note.textProperty().addListener((value, before, after) -> update.run());
        update.run();
        Label name = new Label(photo.fileName());
        name.setWrapText(true);
        content(dialog, new VBox(8, name, new Label("人工结论"), status, new Label("备注"), note, count));
        dialog.setResultConverter(button -> button == save
                ? new Review(ReviewStatus.values()[status.getSelectionModel().getSelectedIndex()], note.getText()) : null);
        return dialog.showAndWait();
    }

    static String reviewLabel(ReviewStatus status) {
        if (status == null) return "未复核";
        return switch (status) {
            case UNREVIEWED -> "未复核";
            case PASS -> "合格";
            case FAIL -> "不合格";
            case REVIEW -> "待复核";
        };
    }

    static void reasons(Window owner, Map<String, Integer> counts) {
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<Void> dialog = dialog(owner, "原因统计", close);
        dialog.getDialogPane().getButtonTypes().setAll(close);
        ListView<String> reasons = new ListView<>();
        reasons.setPrefHeight(230);
        reasons.setPlaceholder(new Label("暂无命中原因"));
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue()
                .reversed().thenComparing(e -> QualityRules.label(e.getKey()), Comparator.naturalOrder()))
                .forEach(e -> reasons.getItems().add(QualityRules.label(e.getKey()) + "   " + e.getValue() + " 张"));
        content(dialog, new VBox(8, reasons));
        dialog.showAndWait();
    }

    static Optional<Export> export(Window owner, int filtered, int all) {
        ButtonType export = new ButtonType("导出", ButtonBar.ButtonData.OK_DONE);
        Dialog<Export> dialog = dialog(owner, "导出 CSV", export);
        ComboBox<String> scope = new ComboBox<>();
        scope.getItems().addAll("当前筛选", "全部已检测");
        scope.getSelectionModel().select(0);
        ComboBox<String> kind = new ComboBox<>();
        kind.getItems().addAll("照片明细", "按日期汇总", "按日期和原因汇总");
        kind.getSelectionModel().select(0);
        Label count = new Label("预计 " + filtered + " 张");
        scope.getSelectionModel().selectedIndexProperty().addListener((o, p, n) ->
                count.setText("预计 " + (n.intValue() == 0 ? filtered : all) + " 张"));
        content(dialog, new VBox(8, new Label("范围"), scope, new Label("内容"), kind, count));
        dialog.setResultConverter(button -> button == export
                ? new Export(ExportScope.values()[scope.getSelectionModel().getSelectedIndex()],
                    ExportKind.values()[kind.getSelectionModel().getSelectedIndex()]) : null);
        return dialog.showAndWait();
    }

    private static <T> Dialog<T> dialog(Window owner, String title, ButtonType submit) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.getDialogPane().getStylesheets().add(
                PhotoDialogs.class.getResource("main-view.css").toExternalForm());
        dialog.getDialogPane().getButtonTypes().setAll(submit,
                new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE));
        return dialog;
    }

    private static void content(Dialog<?> dialog, Node content) {
        dialog.getDialogPane().setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(360);
    }
}
