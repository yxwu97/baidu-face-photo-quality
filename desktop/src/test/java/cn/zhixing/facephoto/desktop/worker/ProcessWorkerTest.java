package cn.zhixing.facephoto.desktop.worker;

import cn.zhixing.facephoto.core.BatchService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessWorkerTest {
    private ProcessWorker worker(String mode, Duration measure) throws IOException {
        Path java = Path.of(System.getenv("JAVA11_HOME"), "bin/java.exe");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        String classpath = root.resolve("detector-baidu-windows/target/test-classes")
                + System.getProperty("path.separator") + System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", classpath,
                "cn.zhixing.facephoto.worker.windows.FakeWorker", mode);
        return new ProcessWorker(builder, new ProcessWorker.Timeouts(
                Duration.ofSeconds(5), measure, Duration.ofSeconds(2)));
    }

    @Test
    void normalMissingMetricAndRepeatedReplies() throws Exception {
        for (String mode : List.of("NORMAL", "MISSING_METRIC", "DUPLICATE")) {
            try (ProcessWorker worker = worker(mode, Duration.ofSeconds(3))) {
                assertTrue(worker.initialize().path("ok").asBoolean());
                assertTrue(worker.measure(Path.of("C:/测试/照片.jpg")).path("ok").asBoolean());
                assertTrue(worker.measure(Path.of("C:/测试/照片2.jpg")).path("ok").asBoolean());
            }
        }
    }

    @Test
    void timeoutAndExitRetireProcess() throws Exception {
        try (ProcessWorker worker = worker("TIMEOUT", Duration.ofMillis(200))) {
            worker.initialize();
            var error = assertThrows(BatchService.WorkerFailure.class,
                    () -> worker.measure(Path.of("C:/测试/照片.jpg")));
            assertEquals("WORKER_TIMEOUT", error.code());
        }
        try (ProcessWorker worker = worker("EXIT", Duration.ofSeconds(3))) {
            worker.initialize();
            var error = assertThrows(BatchService.WorkerFailure.class,
                    () -> worker.measure(Path.of("C:/测试/照片.jpg")));
            assertEquals("WORKER_EXIT", error.code());
        }
    }
}
