package cn.zhixing.facephoto.desktop;

import cn.zhixing.facephoto.core.BatchModels.*;
import cn.zhixing.facephoto.core.BatchService;
import cn.zhixing.facephoto.core.PhotoSource;
import cn.zhixing.facephoto.desktop.data.SqliteBatchStore;
import cn.zhixing.facephoto.protocol.WorkerFrames;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DesktopUiTest {
    @TempDir Path temporary;

    @Test
    void realFxmlAndFourDialogsWithSqliteAndPhotoPreview() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.complete(null);
        });
        started.get(10, TimeUnit.SECONDS);
        Path fixtures = Path.of("..", "fixtures", "jpeg").toAbsolutePath().normalize();
        Path root = Files.createDirectory(temporary.resolve("照片"));
        Files.copy(fixtures.resolve("02-single-face.jpg"), root.resolve("090001_未识别_rec0_live96.jpg"));
        Files.copy(fixtures.resolve("09-exif-rotate-6.jpg"), root.resolve("090002_未识别_rec0_live96.jpg"));

        BatchService service = new BatchService(new SqliteBatchStore(temporary.resolve("results.db")), this::stub);
        Stage[] window = new Stage[1];
        MainController[] controller = new MainController[1];
        try {
            var batch = service.select(root);
            assertEquals(BatchState.COMPLETE, service.run(batch.id()).state());
            var snapshot = service.snapshot(batch.id(), Filter.ALL, 0, 10);
            fx(() -> {
                FXMLLoader loader = new FXMLLoader(FacePhotoApplication.class.getResource("main-view.fxml"));
                Parent view = loader.load();
                controller[0] = loader.getController();
                window[0] = new Stage();
                window[0].setScene(new Scene(view, 1220, 730));
                window[0].show();
                return null;
            });
            for (String field : new String[]{"service", "batch", "snapshot"}) {
                var target = MainController.class.getDeclaredField(field);
                target.setAccessible(true);
                Object value = switch (field) {
                    case "service" -> service;
                    case "batch" -> batch;
                    default -> snapshot;
                };
                fx(() -> { target.set(controller[0], value); return null; });
            }
            fx(() -> {
                var render = MainController.class.getDeclaredMethod("render", Snapshot.class);
                render.setAccessible(true);
                render.invoke(controller[0], snapshot);
                window[0].setWidth(960);
                window[0].getScene().getRoot().applyCss();
                window[0].getScene().getRoot().layout();
                assertTrue(window[0].getScene().getRoot().lookup("#table").getBoundsInParent().getWidth() > 200);
                return null;
            });

            var rotated = PhotoPreview.load(fixtures, "09-exif-rotate-6.jpg",
                    PhotoSource.sha256(fixtures.resolve("09-exif-rotate-6.jpg")), 200);
            assertTrue(rotated.matchesRevision());
            assertTrue(rotated.image().getHeight() > rotated.image().getWidth());
            assertFalse(PhotoPreview.load(root, snapshot.rows().getFirst().relativePath(), "a".repeat(64), 180)
                    .matchesRevision());

            var rules = fx(() -> {
                Platform.runLater(() -> {
                    DialogPane pane = dialogPane(window[0], "检测设置");
                    pane.lookupAll(".button").stream().filter(node -> node instanceof Button button
                            && button.getText().equals("恢复默认值")).findFirst()
                            .map(Button.class::cast).orElseThrow().fire();
                    assertTrue(pane.lookupAll(".text-field").stream().filter(TextField.class::isInstance)
                            .map(TextField.class::cast).anyMatch(field -> field.getText().equals("100")));
                    fire(pane, "应用并重新判定");
                });
                return PhotoDialogs.settings(window[0], snapshot.rules()).orElseThrow();
            });
            assertEquals(100, rules.faceWidth());
            assertFalse(rules.lightEnabled());

            fx(() -> {
                Platform.runLater(() -> {
                    DialogPane pane = dialogPane(window[0], "人工复核");
                    TextArea note = (TextArea) pane.lookup(".text-area");
                    note.setText("😀".repeat(301));
                    Button submit = findButton(pane, "保存复核");
                    assertTrue(submit.isDisabled());
                    note.setText("人工确认");
                    assertFalse(submit.isDisabled());
                    submit.fire();
                });
                var result = PhotoDialogs.review(window[0], snapshot.rows().getFirst()).orElseThrow();
                assertEquals("人工确认", result.note());
                return null;
            });
            fx(() -> {
                Platform.runLater(() -> fire(dialogPane(window[0], "原因统计"), "关闭"));
                PhotoDialogs.reasons(window[0], snapshot.reasonCounts());
                return null;
            });
            fx(() -> {
                Platform.runLater(() -> fire(dialogPane(window[0], "导出 CSV"), "导出"));
                var export = PhotoDialogs.export(window[0], 2, 2).orElseThrow();
                assertEquals(ExportScope.FILTERED, export.scope());
                assertEquals(ExportKind.DETAIL, export.kind());
                return null;
            });
        } finally {
            fx(() -> {
                if (controller[0] != null) controller[0].shutdown();
                if (window[0] != null) window[0].hide();
                return null;
            });
            service.close();
            Platform.exit();
        }
    }

    private BatchService.Worker stub() {
        return new BatchService.Worker() {
            @Override public ObjectNode initialize() {
                ObjectNode data = WorkerFrames.object().put("authorized", true).put("engineId", "test-double")
                        .put("engineVersion", "1").put("metricSemanticsVersion", "test-1")
                        .put("measurementConfigFingerprint", "a".repeat(64));
                data.putObject("probes").put("singleFace", true).put("noFace", true);
                data.putArray("capabilities").add("faceCount").add("boxes").add("pose").add("blur")
                        .add("occlusion").add("illumination");
                return WorkerFrames.success("1", "initialize", data);
            }
            @Override public ObjectNode measure(Path path) throws java.io.IOException {
                ObjectNode data = WorkerFrames.object().put("sha256", PhotoSource.sha256(path))
                        .put("faceCount", 1).put("blur", 0.3).put("illumination", 0.8);
                data.putObject("image").put("sourceWidth", 512).put("sourceHeight", 600)
                        .put("width", 512).put("height", 600).put("orientation", 1);
                data.putArray("boxes").addObject().put("x", 50).put("y", 50).put("width", 200)
                        .put("height", 200).put("score", 0.99);
                data.putObject("pose").put("yaw", 1).put("pitch", 2).put("roll", 3);
                var occlusion = data.putObject("occlusion");
                for (String key : new String[]{"leftEye", "rightEye", "nose", "mouth", "leftCheek", "rightCheek", "chin"}) {
                    occlusion.put(key, 0.1);
                }
                data.putArray("missing");
                return WorkerFrames.success("2", "measure", data);
            }
            @Override public void close() {}
        };
    }

    private static DialogPane dialogPane(Stage owner, String title) {
        return new ArrayList<>(Window.getWindows()).stream().filter(window -> window instanceof Stage stage
                && stage != owner && stage.getTitle().equals(title)).map(window ->
                (DialogPane) window.getScene().getRoot()).findFirst().orElseThrow();
    }

    private static Button findButton(DialogPane pane, String label) {
        ButtonType type = pane.getButtonTypes().stream().filter(button -> button.getText().equals(label))
                .findFirst().orElseThrow();
        return (Button) pane.lookupButton(type);
    }

    private static void fire(DialogPane pane, String label) {
        findButton(pane, label).fire();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> response = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                response.complete(action.call());
            } catch (Throwable error) {
                response.completeExceptionally(error);
            }
        });
        return response.get(20, TimeUnit.SECONDS);
    }
}
