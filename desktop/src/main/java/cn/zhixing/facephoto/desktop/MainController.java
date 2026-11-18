package cn.zhixing.facephoto.desktop;

import cn.zhixing.facephoto.core.BatchModels.Batch;
import cn.zhixing.facephoto.core.BatchModels.BatchState;
import cn.zhixing.facephoto.core.BatchModels.Filter;
import cn.zhixing.facephoto.core.BatchModels.PhotoResult;
import cn.zhixing.facephoto.core.BatchModels.Reason;
import cn.zhixing.facephoto.core.BatchModels.Snapshot;
import cn.zhixing.facephoto.core.BatchModels.Status;
import cn.zhixing.facephoto.core.BatchService;
import cn.zhixing.facephoto.core.QualityRules;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainController {
    @FXML private Label rootLabel, statusLabel, progressLabel, pageLabel, previewMessage, dimensions;
    @FXML private Label resultTitle, reasonText, captureText, reviewText;
    @FXML private Button settingsButton, exportButton, directoryButton, batchButton, reasonButton;
    @FXML private Button pageBack, pageForward, photoBack, photoForward, reviewButton;
    @FXML private ListView<DateEntry> dateList;
    @FXML private ComboBox<ReasonEntry> reasonBox;
    @FXML private TextField search;
    @FXML private TableView<PhotoResult> table;
    @FXML private TableColumn<PhotoResult, PhotoResult> pictureColumn, nameColumn, stateColumn;
    @FXML private TableColumn<PhotoResult, String> reasonColumn;
    @FXML private HBox stateCards;
    @FXML private ProgressBar progress;
    @FXML private StackPane previewArea;
    @FXML private ImageView preview;
    @FXML private Canvas overlay;
    @FXML private CheckBox showBoxes;
    @FXML private TitledPane capturePane;
    @FXML private GridPane metricTable;

    private record DateEntry(String key, int count) {
        @Override public String toString() {
            return (key == null ? "全部日期" : key.isEmpty() ? "无日期" : key) + "  " + count;
        }
    }

    private record ReasonEntry(String code) {
        @Override public String toString() {
            return code == null ? "全部原因" : QualityRules.label(code);
        }
    }

    private record ThumbKey(Path root, String relative, String sha256) {}

    private final ExecutorService commands = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("photo-command").factory());
    private final ExecutorService detector = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("photo-detection").factory());
    private final ExecutorService reads = Executors.newFixedThreadPool(2,
            Thread.ofVirtual().name("photo-query-", 0).factory());
    private final ExecutorService images = Executors.newFixedThreadPool(3,
            Thread.ofVirtual().name("photo-image-", 0).factory());
    private final LinkedHashMap<ThumbKey, Image> thumbs = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<Status, ToggleButton> cards = new EnumMap<>(Status.class);
    private final PauseTransition queryDelay = new PauseTransition(Duration.millis(120));
    private final Timeline poll = new Timeline(new KeyFrame(Duration.millis(500), e -> refresh()));
    private BatchService service;
    private Batch batch;
    private Snapshot snapshot;
    private Status status;
    private String date, reason;
    private int page, pageSize = 8;
    private long queryGeneration, previewGeneration;
    private boolean mutating, running, cancelling, updating, closing, selectLast, boxAllowed;
    private boolean queryRunning, queryPending;
    private ToggleButton allCard;
    private PhotoResult selected;
    private Path selectedRoot;

    @FXML
    private void initialize() {
        buildCards();
        setupTable();
        showPhoto(null);
        updateControls();
        reasonBox.getItems().setAll(new ReasonEntry(null));
        reasonBox.getSelectionModel().selectFirst();
        queryDelay.setOnFinished(event -> refresh());
        poll.setCycleCount(Timeline.INDEFINITE);
        dateList.getSelectionModel().selectedItemProperty().addListener((o, previous, current) -> {
            if (updating || current == null) return;
            date = current.key;
            reason = null;
            updating = true;
            reasonBox.getSelectionModel().selectFirst();
            updating = false;
            changeFilter();
        });
        reasonBox.valueProperty().addListener((o, previous, current) -> {
            if (!updating && current != null) {
                reason = current.code;
                changeFilter();
            }
        });
        search.textProperty().addListener((o, previous, current) -> changeFilter());
        preview.fitWidthProperty().bind(previewArea.widthProperty());
        preview.fitHeightProperty().bind(previewArea.heightProperty());
        overlay.setManaged(false);
        overlay.widthProperty().bind(previewArea.widthProperty());
        overlay.heightProperty().bind(previewArea.heightProperty());
        overlay.widthProperty().addListener(o -> drawBoxes());
        overlay.heightProperty().addListener(o -> drawBoxes());
        showBoxes.selectedProperty().addListener(o -> drawBoxes());
    }

    void connect(Callable<BatchService> factory) {
        mutating = true;
        updateControls();
        commands.execute(() -> {
            try {
                BatchService connected = factory.call();
                Platform.runLater(() -> {
                    if (closing) {
                        try {
                            connected.close();
                        } catch (IOException ignored) {
                            // The application is already shutting down.
                        }
                        return;
                    }
                    service = connected;
                    mutating = false;
                    updateControls();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> {
                    mutating = false;
                    statusLabel.setText("无法打开结果库 · " + failureMessage(failure));
                    updateControls();
                });
            }
        });
    }

    private void buildCards() {
        ToggleGroup group = new ToggleGroup();
        allCard = addCard("已检测", null, group);
        for (Status value : Status.values()) cards.put(value, addCard(value.label(), value, group));
        allCard.setSelected(true);
    }

    private ToggleButton addCard(String label, Status value, ToggleGroup group) {
        ToggleButton button = new ToggleButton();
        Label count = new Label("0");
        count.getStyleClass().add("card-count");
        VBox content = new VBox(3, new Label(label), count);
        content.setAlignment(Pos.CENTER);
        button.setGraphic(content);
        button.setUserData(count);
        button.setAccessibleText(label + " 0");
        button.getStyleClass().add("state-card");
        if (value != null) button.getStyleClass().add(statusStyle(value));
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        button.setOnAction(event -> {
            button.setSelected(true);
            status = value;
            changeFilter();
        });
        stateCards.getChildren().add(button);
        return button;
    }

    private void setupTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        List.of(pictureColumn, nameColumn, stateColumn).forEach(column ->
                column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue())));
        table.getColumns().forEach(column -> column.setSortable(false));
        pictureColumn.setCellFactory(column -> thumbnailCell());
        nameColumn.setCellFactory(column -> nameCell());
        stateColumn.setCellFactory(column -> stateCell());
        reasonColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(reasonSummary(c.getValue())));
        reasonColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTooltip(empty || item.isBlank() ? null : new Tooltip(item));
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, previous, current) -> {
            if (!updating) showPhoto(current);
        });
        table.heightProperty().addListener((o, previous, current) -> updatePageSize());
    }

    private void updatePageSize() {
        int visible = Math.max(1, (int) ((table.getHeight() - 34) / table.getFixedCellSize()));
        if (visible == pageSize) return;
        int absoluteIndex = page * pageSize + Math.max(0, table.getSelectionModel().getSelectedIndex());
        page = absoluteIndex / visible;
        pageSize = visible;
        if (batch != null) {
            queryGeneration++;
            queryDelay.playFromStart();
        }
    }

    private TableCell<PhotoResult, PhotoResult> thumbnailCell() {
        return new TableCell<>() {
            private final ImageView thumbnail = new ImageView();
            private long generation;
            {
                thumbnail.setFitWidth(53);
                thumbnail.setFitHeight(42);
                thumbnail.setPreserveRatio(true);
            }
            @Override protected void updateItem(PhotoResult item, boolean empty) {
                super.updateItem(item, empty);
                long mine = ++generation;
                thumbnail.setImage(null);
                setGraphic(empty ? null : thumbnail);
                if (empty || item == null || batch == null || closing) return;
                ThumbKey key = new ThumbKey(batch.root(), item.relativePath(), item.sha256());
                Image cached = thumbs.get(key);
                if (cached != null) {
                    thumbnail.setImage(cached);
                    return;
                }
                images.execute(() -> {
                    try {
                        Image image = PhotoPreview.load(key.root, key.relative, null, 104).image();
                        Platform.runLater(() -> {
                            if (closing) return;
                            thumbs.put(key, image);
                            if (thumbs.size() > 96) thumbs.pollFirstEntry();
                            if (generation == mine) thumbnail.setImage(image);
                        });
                    } catch (IOException | RuntimeException ignored) {
                        // The selected photo's full preview shows the reason.
                    }
                });
            }
        };
    }

    private static TableCell<PhotoResult, PhotoResult> nameCell() {
        return new TableCell<>() {
            private final Label name = new Label();
            private final Label date = new Label();
            private final VBox line = new VBox(2, name, date);
            {
                date.getStyleClass().add("muted");
            }
            @Override protected void updateItem(PhotoResult photo, boolean empty) {
                super.updateItem(photo, empty);
                setGraphic(empty || photo == null ? null : line);
                setTooltip(empty || photo == null ? null : new Tooltip(photo.fileName()));
                if (!empty && photo != null) {
                    name.setText(photo.fileName());
                    date.setText((text(photo.date()) + " " + text(photo.time())).strip());
                }
            }
        };
    }

    private static TableCell<PhotoResult, PhotoResult> stateCell() {
        return new TableCell<>() {
            @Override protected void updateItem(PhotoResult photo, boolean empty) {
                super.updateItem(photo, empty);
                getStyleClass().removeIf(style -> style.startsWith("status-"));
                Status status = empty || photo == null ? null : photo.status();
                setText(status == null ? null : status.label());
                if (status != null) getStyleClass().add(statusStyle(status));
            }
        };
    }

    private static String statusStyle(Status value) {
        return "status-" + value.name().toLowerCase(Locale.ROOT);
    }

    @FXML
    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择抓拍目录");
        if (batch != null && batch.root().toFile().isDirectory()) chooser.setInitialDirectory(batch.root().toFile());
        var result = chooser.showDialog(window());
        if (result == null) return;
        statusLabel.setText("正在扫描目录…");
        submit(() -> service.select(result.toPath()), this::showBatch);
    }

    private void showBatch(Batch next) {
        queryGeneration++;
        batch = next;
        snapshot = null;
        thumbs.clear();
        updating = true;
        date = null;
        reason = null;
        status = null;
        page = 0;
        allCard.setSelected(true);
        search.setText("");
        table.getItems().clear();
        updating = false;
        showPhoto(null);
        renderBatch();
        refresh();
    }

    @FXML
    private void performBatchAction() {
        Batch current = batch;
        if (running) {
            cancelling = true;
            updateControls();
            commands.execute(() -> {
                try {
                    service.cancel(current.id());
                } catch (IOException failure) {
                    Platform.runLater(() -> notice(failure));
                }
            });
            return;
        }
        if (current.state() == BatchState.COMPLETE) {
            statusLabel.setText("正在重新扫描…");
            submit(() -> service.rescan(current.id()), next -> {
                showBatch(next);
                if (next.total() > 0) start(next);
            });
        } else {
            start(current);
        }
    }

    private void start(Batch current) {
        running = true;
        cancelling = false;
        statusLabel.setText("正在检查本机检测引擎…");
        updateControls();
        poll.playFromStart();
        work(detector, () -> service.run(current.id()), next -> finishRun(next, null),
                error -> finishRun(null, error));
    }

    private void finishRun(Batch next, Exception failure) {
        running = false;
        cancelling = false;
        poll.stop();
        if (next != null) {
            batch = next;
            renderBatch();
        }
        if (failure != null) notice(failure);
        updateControls();
        refresh();
    }

    private void changeFilter() {
        if (updating) return;
        page = 0;
        selectLast = false;
        queryGeneration++;
        previewGeneration++;
        selectedRoot = null;
        queryDelay.playFromStart();
    }

    private Filter filter() {
        return new Filter(date, status, reason, search.getText());
    }

    private void refresh() {
        if (batch == null || service == null || closing) return;
        if (queryRunning) {
            queryPending = true;
            return;
        }
        queryRunning = true;
        long generation = queryGeneration;
        long id = batch.id();
        Filter filters = filter();
        int size = pageSize;
        int index = page;
        work(reads, () -> service.snapshot(id, filters, index, size), result -> {
            queryRunning = false;
            if (queryGeneration != generation) {
                drainQuery();
                return;
            }
            if (index > 0 && result.rows().isEmpty()) {
                page = Math.max(0, Math.ceilDiv(result.filteredCount(), size) - 1);
                refresh();
                return;
            }
            render(result);
            drainQuery();
        }, failure -> {
            queryRunning = false;
            if (queryGeneration == generation) notice(failure);
            drainQuery();
        });
    }

    private void drainQuery() {
        if (queryPending) {
            queryPending = false;
            refresh();
        }
    }

    private void render(Snapshot next) {
        snapshot = next;
        batch = next.batch();
        renderBatch();
        updating = true;
        renderDates(next);
        renderReasons(next);
        int checked = next.statusCounts().values().stream().mapToInt(Integer::intValue).sum();
        cardCount(allCard, "已检测", checked);
        cards.forEach((value, card) ->
                cardCount(card, value.label(), next.statusCounts().getOrDefault(value, 0)));
        updateRows(next.rows());
        updating = false;
        showPhoto(table.getSelectionModel().getSelectedItem());
    }

    private void renderDates(Snapshot next) {
        List<DateEntry> entries = new ArrayList<>();
        entries.add(new DateEntry(null, next.batch().total()));
        next.dates().forEach((key, count) -> entries.add(new DateEntry(key, count)));
        if (!dateList.getItems().equals(entries)) dateList.getItems().setAll(entries);
        entries.stream().filter(entry -> Objects.equals(entry.key, date)).findFirst()
                .ifPresent(dateList.getSelectionModel()::select);
    }

    private static void cardCount(ToggleButton card, String title, int count) {
        ((Label) card.getUserData()).setText(Integer.toString(count));
        card.setAccessibleText(title + " " + count);
    }

    private void renderReasons(Snapshot next) {
        List<ReasonEntry> entries = new ArrayList<>();
        entries.add(new ReasonEntry(null));
        next.reasonCounts().keySet().forEach(code -> entries.add(new ReasonEntry(code)));
        if (reason != null && entries.stream().noneMatch(entry -> reason.equals(entry.code))) {
            entries.add(new ReasonEntry(reason));
        }
        if (!reasonBox.getItems().equals(entries)) reasonBox.getItems().setAll(entries);
        entries.stream().filter(entry -> Objects.equals(entry.code, reason)).findFirst()
                .ifPresent(reasonBox.getSelectionModel()::select);
    }

    private void updateRows(List<PhotoResult> items) {
        long previous = selected == null ? -1 : selected.itemId();
        if (!table.getItems().equals(items)) table.getItems().setAll(items);
        int index = selectLast ? items.size() - 1 : 0;
        if (!selectLast) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).itemId() == previous) {
                    index = i;
                    break;
                }
            }
        }
        selectLast = false;
        if (items.isEmpty()) table.getSelectionModel().clearSelection();
        else table.getSelectionModel().select(index);
    }

    private void renderBatch() {
        rootLabel.setText(batch.root().toString());
        rootLabel.setTooltip(new Tooltip(batch.root().toString()));
        progress.setProgress(batch.total() == 0 ? 0 : (double) batch.committed() / batch.total());
        progressLabel.setText("%,d / %,d 张".formatted(batch.committed(), batch.total()));
        String message = switch (batch.state()) {
            case READY -> batch.total() > 0 ? "等待检测" : "目录中没有 JPEG";
            case CHECKING -> "正在检查本机检测引擎…";
            case RUNNING -> "正在检测";
            case CANCELLING -> "正在取消";
            case CANCELLED -> "已取消，可继续检测";
            case PAUSED -> batchError(batch.errorCode());
            case COMPLETE -> "检测完成";
        };
        if (batch.unreadableDirectories() > 0) message += " · 跳过 " + batch.unreadableDirectories() + " 个不可读目录";
        if (!mutating) statusLabel.setText(message);
        updateControls();
    }

    static String batchError(String code) {
        if (code == null) return "检测暂停";
        if (code.startsWith("SDK_INIT_FAILED") || code.startsWith("SDK_NOT_AUTHORIZED")) {
            if (code.endsWith("(-1015)")) {
                return "缺少离线授权文件，请将 license.ini 和 license.key 放入程序的 app/worker/sdk/license 后重试（-1015）";
            }
            if (code.endsWith("(-1018)")) {
                return "离线授权已过期，请更新本机授权文件后重试（-1018）";
            }
            if (code.endsWith("(-1019)")) {
                return "授权序列号已使用，请在百度控制台核对本机设备绑定并更新授权（-1019）";
            }
            return "SDK 初始化失败，请检查离线授权后重试（" + code + "）";
        }
        return switch (code) {
            case "ENGINE_UNAVAILABLE", "SDK_DEPENDENCY_UNAVAILABLE" -> "本机检测引擎未就绪";
            case "SDK_PROBE_FAILED" -> "检测引擎未通过对照图片检查";
            case "DATABASE_WRITE_FAILED" -> "无法写入结果库，检测已暂停";
            default -> "检测暂停（" + code + "）";
        };
    }

    @FXML private void previousPage() { movePage(-1, false); }
    @FXML private void nextPage() { movePage(1, false); }
    @FXML private void previousPhoto() { movePhoto(-1); }
    @FXML private void nextPhoto() { movePhoto(1); }

    private void movePage(int change, boolean last) {
        int target = page + change;
        if (target >= 0 && target < pages()) {
            queryGeneration++;
            page = target;
            selectLast = last;
            previewGeneration++;
            selectedRoot = null;
            refresh();
        }
    }

    private void movePhoto(int direction) {
        int target = table.getSelectionModel().getSelectedIndex() + direction;
        if (target >= 0 && target < table.getItems().size()) {
            table.getSelectionModel().select(target);
            table.scrollTo(target);
        } else {
            movePage(direction, direction < 0);
        }
    }

    private int pages() {
        return snapshot == null ? 0 : Math.ceilDiv(snapshot.filteredCount(), pageSize);
    }

    private void updateControls() {
        directoryButton.setDisable(service == null || mutating || running);
        boolean noBatch = batch == null;
        BatchState state = noBatch ? BatchState.READY : batch.state();
        batchButton.setText(running ? cancelling ? "正在取消" : "取消检测" : switch (state) {
            case COMPLETE -> "重新检测";
            case PAUSED -> "重试检测";
            case CANCELLED -> "继续检测";
            default -> "开始检测";
        });
        batchButton.setDisable(noBatch || mutating || cancelling || batch.total() == 0);
        settingsButton.setDisable(snapshot == null || mutating);
        exportButton.setDisable(noBatch || mutating || batch.committed() == 0);
        reasonButton.setDisable(snapshot == null);
        int pageCount = pages();
        pageLabel.setText(pageCount == 0 ? "0 页" : (page + 1) + " / " + pageCount + " 页");
        pageBack.setDisable(page <= 0);
        pageForward.setDisable(page + 1 >= pageCount);
        int index = table.getSelectionModel().getSelectedIndex();
        photoBack.setDisable(index < 0 || (index == 0 && page == 0));
        photoForward.setDisable(index < 0 || (index == table.getItems().size() - 1 && page + 1 >= pageCount));
        reviewButton.setDisable(selected == null || selected.revisionId() == null || mutating);
        showBoxes.setDisable(!boxAllowed);
    }

    private void showPhoto(PhotoResult photo) {
        Path root = batch == null ? null : batch.root();
        boolean samePicture = photo != null && selected != null && Objects.equals(root, selectedRoot)
                && photo.itemId() == selected.itemId() && Objects.equals(photo.sha256(), selected.sha256());
        selected = photo;
        selectedRoot = root;
        DetailEvidence.render(snapshot, photo, dimensions, resultTitle, reasonText,
                metricTable, capturePane, captureText, reviewText);
        if (!samePicture) loadPreview(photo, root);
        else drawBoxes();
        updateControls();
    }

    private void loadPreview(PhotoResult photo, Path root) {
        long generation = ++previewGeneration;
        boxAllowed = false;
        preview.setImage(null);
        previewMessage.setText(photo == null ? "暂无原图" : "正在读取原图…");
        previewMessage.setVisible(true);
        drawBoxes();
        if (photo == null) return;
        work(images, () -> PhotoPreview.load(root, photo.relativePath(), photo.sha256(), 940), loaded -> {
            if (generation != previewGeneration) return;
            preview.setImage(loaded.image());
            boolean revised = photo.sha256() != null && !loaded.matchesRevision();
            previewMessage.setText(revised ? "原图已变化" : "");
            previewMessage.setVisible(revised);
            boxAllowed = loaded.matchesRevision() && photo.measurement() != null
                    && !photo.measurement().path("boxes").isEmpty();
            drawBoxes();
            updateControls();
        }, error -> {
            if (generation == previewGeneration) previewMessage.setText("原图不可用");
        });
    }

    private void drawBoxes() {
        var graphics = overlay.getGraphicsContext2D();
        double w = overlay.getWidth();
        double h = overlay.getHeight();
        graphics.clearRect(0, 0, w, h);
        if (!boxAllowed || !showBoxes.isSelected() || selected == null || selected.measurement() == null) return;
        var image = selected.measurement().path("image");
        double sw = image.path("width").asDouble();
        double sh = image.path("height").asDouble();
        if (sw <= 0 || sh <= 0) return;
        double scale = Math.min(w / sw, h / sh);
        double dx = (w - sw * scale) / 2;
        double dy = (h - sh * scale) / 2;
        graphics.setStroke(Color.web("#33d6a2"));
        graphics.setLineWidth(2);
        for (var box : selected.measurement().path("boxes")) {
            double x = Math.max(0, box.path("x").asDouble());
            double y = Math.max(0, box.path("y").asDouble());
            double right = Math.min(sw, box.path("x").asDouble() + box.path("width").asDouble());
            double bottom = Math.min(sh, box.path("y").asDouble() + box.path("height").asDouble());
            if (right > x && bottom > y) {
                graphics.strokeRect(dx + x * scale, dy + y * scale, (right - x) * scale, (bottom - y) * scale);
            }
        }
    }

    @FXML
    private void openSettings() {
        long id = batch.id();
        PhotoDialogs.settings(window(), snapshot.rules()).ifPresent(rules ->
                submit(() -> service.applyRules(id, rules), ignored -> refresh()));
    }

    @FXML
    private void openReview() {
        PhotoResult photo = selected;
        PhotoDialogs.review(window(), photo).ifPresent(review -> submit(() -> {
            service.review(photo.revisionId(), review.status(), review.note());
            return true;
        }, ignored -> refresh()));
    }

    @FXML
    private void openReasons() {
        PhotoDialogs.reasons(window(), snapshot.reasonCounts());
    }

    @FXML
    private void openExport() {
        Snapshot current = snapshot;
        if (current == null) return;
        Filter filters = filter();
        PhotoDialogs.export(window(), current.filteredCount(), current.batch().committed()).ifPresent(selection -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存 CSV");
            chooser.setInitialFileName(switch (selection.kind()) {
                case DETAIL -> "照片检测明细.csv";
                case DATE -> "按日期汇总.csv";
                case DATE_REASON -> "按日期和原因汇总.csv";
            });
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));
            var output = chooser.showSaveDialog(window());
            if (output != null) {
                submit(() -> service.export(current.batch().id(), filters, selection.scope(),
                        selection.kind(), output.toPath()), result ->
                        statusLabel.setText("已导出 " + result.count() + " 张 · " + result.file().getFileName()));
            }
        });
    }

    private <T> void submit(Callable<T> action, Consumer<T> result) {
        mutating = true;
        updateControls();
        work(commands, action, value -> {
            mutating = false;
            result.accept(value);
            updateControls();
        }, error -> {
            mutating = false;
            updateControls();
            notice(error);
        });
    }

    private <T> void work(ExecutorService executor, Callable<T> action, Consumer<T> result,
                          Consumer<Exception> failure) {
        executor.execute(() -> {
            try {
                T value = action.call();
                Platform.runLater(() -> {
                    if (!closing) result.accept(value);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (!closing) failure.accept(e);
                });
            }
        });
    }

    private void notice(Exception failure) {
        statusLabel.setText(failureMessage(failure));
    }

    private static String failureMessage(Exception failure) {
        return failure.getMessage() == null ? "操作失败" : failure.getMessage();
    }

    private Window window() { return table.getScene().getWindow(); }

    static String text(Object value) { return value == null ? "" : value.toString(); }

    static String reasonSummary(PhotoResult photo) {
        return String.join("、", photo.reasons().stream().map(Reason::label).toList());
    }

    void shutdown() throws IOException {
        closing = true;
        poll.stop();
        queryDelay.stop();
        try {
            if (service != null) service.close();
        } finally {
            commands.shutdownNow();
            detector.shutdownNow();
            reads.shutdownNow();
            images.shutdownNow();
        }
    }
}
