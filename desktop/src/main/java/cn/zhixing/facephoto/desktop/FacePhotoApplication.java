package cn.zhixing.facephoto.desktop;

import cn.zhixing.facephoto.core.BatchService;
import cn.zhixing.facephoto.desktop.data.SqliteBatchStore;
import cn.zhixing.facephoto.desktop.worker.ProcessWorker;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.Properties;

public final class FacePhotoApplication extends Application {
    private MainController controller;
    private FileHandler log;

    @Override
    public void init() throws IOException {
        Path logs = dataDirectory().resolve("logs");
        Files.createDirectories(logs);
        log = new FileHandler(logs.resolve("application-%g.log").toString(), 1_048_576, 5, true);
        log.setEncoding(StandardCharsets.UTF_8.name());
        log.setFormatter(new SimpleFormatter());
        Logger.getLogger("cn.zhixing.facephoto").addHandler(log);
    }

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(FacePhotoApplication.class.getResource("main-view.fxml"));
        Properties version = new Properties();
        try (var stream = FacePhotoApplication.class.getResourceAsStream("version.properties")) {
            if (stream != null) version.load(stream);
        }
        stage.setTitle("百度离线人脸照片检测工具 · " + version.getProperty("product.version", ""));
        stage.getIcons().add(new Image(FacePhotoApplication.class.getResource("app-icon.png").toExternalForm()));
        stage.setScene(new Scene(loader.load(), 1220, 730));
        stage.setMinWidth(960);
        stage.setMinHeight(520);
        controller = loader.getController();
        stage.show();
        controller.connect(() -> new BatchService(new SqliteBatchStore(dataDirectory().resolve("data/results.db")),
                ProcessWorker.installation(workerDirectory())));
    }

    private static Path dataDirectory() {
        String configured = System.getProperty("facephoto.data.home");
        if (configured != null) return Path.of(configured);
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(local == null || local.isBlank() ? System.getProperty("user.home") : local)
                .resolve("BaiduFacePhotoQuality");
    }

    private static Path workerDirectory() {
        String configured = System.getProperty("facephoto.worker.home");
        if (configured != null) return Path.of(configured);
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null) return Path.of(launcher).toAbsolutePath().getParent().resolve("app/worker");
        Path local = Path.of(".local/worker");
        return Files.isDirectory(local) ? local : Path.of("../.local/worker");
    }

    @Override
    public void stop() throws IOException {
        try {
            if (controller != null) controller.shutdown();
        } finally {
            if (log != null) log.close();
        }
    }

    public static void main(String[] args) {
        System.setProperty("javafx.cachedir", dataDirectory().resolve("cache/javafx").toString());
        launch(args);
    }
}
