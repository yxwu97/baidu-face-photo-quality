package cn.zhixing.facephoto.worker.windows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.zhixing.facephoto.protocol.ProtocolException;
import cn.zhixing.facephoto.protocol.WorkerProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

final class WorkerProtocolTest {
    @TempDir
    Path tempDir;

    private ProtocolExamples examples;

    @BeforeEach
    void loadExamples() throws IOException {
        examples = new ProtocolExamples();
    }

    @ParameterizedTest
    @CsvSource({"NORMAL,measure-ok", "MISSING_METRIC,measure-partial", "FILE_ERROR,measure-file-error"})
    void performsThreeOperations(FakeWorker.Mode mode, String measureCase) throws Exception {
        try (Session worker = start(mode)) {
            exchange(worker, "init-ok");
            exchange(worker, measureCase);
            exchange(worker, "shutdown-ok");
            assertEquals(0, worker.exitCode());
        }
    }

    @Test
    void reportsBatchError() throws Exception {
        try (Session worker = start(FakeWorker.Mode.BATCH_ERROR)) {
            exchange(worker, "init-error");
            exchange(worker, "shutdown-ok");
        }
    }

    @Test
    void rejectsUnsupportedVersionAndAllowsValidRetry() throws Exception {
        ProtocolException unsupported = assertThrows(ProtocolException.class,
                () -> WorkerProtocol.validateRequest(examples.request("bad-version")));
        assertEquals(WorkerProtocol.UNSUPPORTED_VERSION, unsupported.code());
        ObjectNode response = examples.response("measure-ok").put("v", 99);
        assertThrows(ProtocolException.class, () -> WorkerProtocol.validateResponse(response));

        try (Session worker = start(FakeWorker.Mode.NORMAL)) {
            exchange(worker, "bad-version");
            exchange(worker, "init-ok");
            exchange(worker, "shutdown-ok");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{\"v\":1,\"v\":1}", "{}{}", "{broken}"})
    void rejectsInvalidFrames(String line) {
        assertThrows(ProtocolException.class, () -> WorkerProtocol.parseLine(line));
    }

    @Test
    void producesLateResponseWithOriginalId() throws Exception {
        try (Session worker = start(FakeWorker.Mode.LATE)) {
            exchange(worker, "init-ok");
            worker.send(examples.request("measure-ok"));
            Future<String> reply = worker.readLine();
            assertThrows(TimeoutException.class, () -> reply.get(100, TimeUnit.MILLISECONDS));

            // Send a different request while the test double is still finishing the expired request.
            worker.send(examples.request("shutdown-ok"));
            assertFrame(reply.get(5, TimeUnit.SECONDS), "measure-ok");
            assertFrame(worker.readLine().get(5, TimeUnit.SECONDS), "shutdown-ok");
            assertEquals(0, worker.exitCode());
        }
    }

    @Test
    void producesDuplicateResponse() throws Exception {
        try (Session worker = start(FakeWorker.Mode.DUPLICATE)) {
            exchange(worker, "init-ok");
            exchange(worker, "measure-ok");
            assertFrame(worker.readLine().get(5, TimeUnit.SECONDS), "measure-ok");
            exchange(worker, "shutdown-ok");
        }
    }

    @Test
    void canBeReapedAfterTimeout() throws Exception {
        try (Session worker = start(FakeWorker.Mode.TIMEOUT)) {
            exchange(worker, "init-ok");
            worker.send(examples.request("measure-ok"));
            Future<String> reply = worker.readLine();
            assertThrows(TimeoutException.class, () -> reply.get(200, TimeUnit.MILLISECONDS));
            worker.process.destroyForcibly();
            assertTrue(worker.process.waitFor(5, TimeUnit.SECONDS));
            assertNull(reply.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void reportsUnexpectedExitWithoutProtocolReply() throws Exception {
        try (Session worker = start(FakeWorker.Mode.EXIT)) {
            exchange(worker, "init-ok");
            worker.send(examples.request("measure-ok"));
            assertNull(worker.readLine().get(5, TimeUnit.SECONDS));
            assertEquals(70, worker.exitCode());
            assertEquals("TEST_WORKER_EXIT", Files.readString(worker.stderr).strip());
        }
    }

    private Session start(FakeWorker.Mode mode) throws IOException {
        assertEquals(21, Runtime.version().feature(), "Maven 测试使用 JDK 21");
        String home = System.getenv("JAVA11_HOME");
        assertTrue(home != null && !home.isBlank(), "请将 JAVA11_HOME 指向 Java 11 x64 安装目录");
        Path java = Path.of(home, "bin", "java.exe").toAbsolutePath();
        assertTrue(Files.isRegularFile(java), "JAVA11_HOME 下没有 bin/java.exe");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new Session(java, classpath, mode, tempDir.resolve("worker-stderr.log"));
    }

    private void exchange(Session worker, String id) throws Exception {
        ObjectNode request = examples.request(id);
        if (request.path("v").asInt() == WorkerProtocol.VERSION) {
            WorkerProtocol.validateRequest(request);
        }
        worker.send(request);
        assertFrame(worker.readLine().get(5, TimeUnit.SECONDS), id);
    }

    private void assertFrame(String line, String id) throws IOException {
        assertNotNull(line, "Worker 未返回协议帧：" + id);
        ObjectNode response = WorkerProtocol.parseLine(line);
        WorkerProtocol.validateResponse(response);
        assertEquals(examples.response(id), response);
    }

    private static final class Session implements AutoCloseable {
        private final Process process;
        private final Path stderr;
        private final BufferedReader input;
        private final OutputStreamWriter output;
        private final ExecutorService reader = Executors.newSingleThreadExecutor();

        private Session(Path java, String classpath, FakeWorker.Mode mode, Path stderr) throws IOException {
            this.stderr = stderr;
            process = new ProcessBuilder(java.toString(), "-cp", classpath, FakeWorker.class.getName(), mode.name())
                    .redirectError(stderr.toFile())
                    .start();
            input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            output = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }

        private void send(ObjectNode request) throws IOException {
            WorkerProtocol.writeLine(output, request);
        }

        private Future<String> readLine() {
            return reader.submit(input::readLine);
        }

        private int exitCode() throws InterruptedException {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Worker 未按时退出");
            return process.exitValue();
        }

        @Override
        public void close() throws IOException, InterruptedException {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Worker 未被回收");
                }
                try (output; input) {
                    // Close both standard streams after the process has exited.
                }
            } finally {
                reader.shutdownNow();
            }
        }
    }
}
