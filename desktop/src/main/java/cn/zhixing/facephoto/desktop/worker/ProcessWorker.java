package cn.zhixing.facephoto.desktop.worker;

import cn.zhixing.facephoto.core.BatchService;
import cn.zhixing.facephoto.protocol.ProtocolException;
import cn.zhixing.facephoto.protocol.WorkerFrames;
import cn.zhixing.facephoto.protocol.WorkerProtocol;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

public final class ProcessWorker implements BatchService.Worker {
    private static final Logger LOGGER = Logger.getLogger(ProcessWorker.class.getName());
    private static final int FRAME_LIMIT = 64 * 1024;
    private static final String MAIN_CLASS = "cn.zhixing.facephoto.worker.windows.WindowsWorker";
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("worker-timeout").factory());
    private final Process process;
    private final BufferedWriter input;
    private final Timeouts timeouts;
    private final Object dispatch = new Object();
    private volatile Pending pending;
    private volatile BatchService.WorkerFailure fatal;
    private volatile long lastCompleted;
    private volatile boolean shuttingDown;
    private long sequence;

    private record Pending(long id, String operation, CompletableFuture<ObjectNode> response) {}

    record Timeouts(Duration initialize, Duration measure, Duration shutdown) {
        static final Timeouts DEFAULT = new Timeouts(
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    public static BatchService.WorkerFactory installation(Path home) {
        return () -> {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                    || !"amd64".equalsIgnoreCase(System.getProperty("os.arch"))) {
                throw new BatchService.WorkerFailure("ENGINE_UNAVAILABLE", null);
            }
            Path root = home.toAbsolutePath().normalize();
            Path sdk = root.resolve("sdk");
            Path java = root.resolve("runtime/bin/java.exe");
            Path lib = root.resolve("lib");
            if (!Files.isRegularFile(java) || !Files.isRegularFile(sdk.resolve("BaiduFaceApi.dll"))
                    || !Files.isDirectory(lib)) {
                throw new BatchService.WorkerFailure("ENGINE_UNAVAILABLE", null);
            }
            ProcessBuilder builder = new ProcessBuilder(List.of(java.toString(), "-XX:-CreateCoredumpOnCrash",
                    "-Djava.library.path=" + sdk, "-cp", lib + "\\*", MAIN_CLASS))
                    .directory(sdk.toFile());
            var environment = builder.environment();
            environment.put("PATH", sdk + ";" + environment.getOrDefault("PATH", ""));
            return new ProcessWorker(builder, Timeouts.DEFAULT);
        };
    }

    ProcessWorker(ProcessBuilder builder, Timeouts timeouts) throws IOException {
        this.timeouts = timeouts;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new BatchService.WorkerFailure("WORKER_EXIT", e);
        }
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofPlatform().daemon().name("worker-protocol").start(this::receive);
        Thread.ofPlatform().daemon().name("worker-diagnostics").start(this::drainDiagnostics);
    }

    @Override
    public ObjectNode initialize() throws IOException {
        return call(WorkerProtocol.INITIALIZE,
                WorkerFrames.object().put("configurationId", "baidu-windows-x64"), timeouts.initialize);
    }

    @Override
    public ObjectNode measure(Path path) throws IOException {
        return call(WorkerProtocol.MEASURE, WorkerFrames.object().put("path", path.toString()), timeouts.measure);
    }

    private ObjectNode call(String operation, ObjectNode args, Duration timeout) throws IOException {
        synchronized (dispatch) {
            BatchService.WorkerFailure existing = fatal;
            if (existing != null) throw existing;
            long id = ++sequence;
            Pending request = new Pending(id, operation, new CompletableFuture<>());
            pending = request;
            var deadline = WATCHDOG.schedule(() -> {
                if (pending == request && !request.response.isDone()) fail("WORKER_TIMEOUT", null);
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                existing = fatal;
                if (existing != null) throw existing;
                try {
                    WorkerProtocol.writeLine(input, WorkerFrames.request(Long.toString(id), operation, args));
                } catch (IOException e) {
                    throw fail("WORKER_EXIT", e);
                }
                return request.response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw fail("WORKER_TIMEOUT", null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw fail("WORKER_INTERRUPTED", null);
            } catch (ExecutionException e) {
                throw (BatchService.WorkerFailure) e.getCause();
            } finally {
                deadline.cancel(false);
                lastCompleted = id;
                pending = null;
            }
        }
    }

    private void receive() {
        try (Reader output = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8.newDecoder()))) {
            for (String line; (line = readFrame(output)) != null;) {
                accept(line);
            }
            Pending request = pending;
            if (!shuttingDown || request != null && !request.response.isDone()) fail("WORKER_EXIT", null);
        } catch (ProtocolException | java.nio.charset.CharacterCodingException e) {
            fail("PROTOCOL_ERROR", null);
        } catch (IOException e) {
            fail("WORKER_EXIT", null);
        }
    }

    private void accept(String line) throws ProtocolException {
        if (line.isBlank()) return;
        if (!line.stripLeading().startsWith("{") && !line.contains(WorkerProtocol.CHANNEL)) {
            LOGGER.fine("Worker 输出了一条原生诊断");
            return;
        }
        ObjectNode frame = WorkerProtocol.parseLine(line);
        WorkerProtocol.validateResponse(frame);
        Pending request = pending;
        String id = frame.path("id").textValue();
        if (request != null && Long.toString(request.id).equals(id)
                && request.operation.equals(frame.path("op").textValue())) {
            request.response.complete(frame);
            return;
        }
        long numeric;
        try {
            numeric = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ProtocolException("PROTOCOL_ERROR", "异常的响应编号");
        }
        if (numeric <= 0 || numeric > lastCompleted) {
            throw new ProtocolException("PROTOCOL_ERROR", "响应与当前请求不匹配");
        }
    }

    private static String readFrame(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int current;
        while ((current = reader.read()) != -1 && current != '\n') {
            if (line.length() == FRAME_LIMIT) {
                throw new ProtocolException("PROTOCOL_ERROR", "响应行超过上限");
            }
            line.append((char) current);
        }
        if (current == -1) {
            if (!line.isEmpty()) throw new ProtocolException("PROTOCOL_ERROR", "响应行不完整");
            return null;
        }
        return line.toString();
    }

    private void drainDiagnostics() {
        // Vendor output may include device or path details. Drain it independently of the protocol.
        try (InputStream stream = process.getErrorStream()) {
            byte[] chunk = new byte[4096];
            boolean reported = false;
            while (stream.read(chunk) != -1) {
                if (!reported) {
                    LOGGER.fine("Worker 输出了标准错误诊断");
                    reported = true;
                }
            }
        } catch (IOException ignored) {
            // This stream closes as part of process retirement.
        }
    }

    private synchronized BatchService.WorkerFailure fail(String code, Throwable cause) {
        Pending request = pending;
        if (fatal == null) {
            fatal = new BatchService.WorkerFailure(code, cause);
            LOGGER.warning(() -> "Worker 请求 " + (request == null ? 0 : request.id) + " " + code);
        }
        if (request != null) request.response.completeExceptionally(fatal);
        if (process.isAlive()) process.destroyForcibly();
        return fatal;
    }

    @Override
    public void abort() {
        fail("WORKER_CANCELLED", null);
    }

    @Override
    public void close() {
        long deadline = System.nanoTime() + timeouts.shutdown.toNanos();
        try {
            if (fatal == null && process.isAlive()) {
                shuttingDown = true;
                call(WorkerProtocol.SHUTDOWN, WorkerFrames.object(), timeouts.shutdown);
            }
            if (process.isAlive() && !process.waitFor(Math.max(0, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS)) {
                abort();
            }
        } catch (IOException e) {
            abort();
        } catch (InterruptedException e) {
            abort();
            Thread.currentThread().interrupt();
        }
    }
}
