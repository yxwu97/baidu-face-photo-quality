package cn.zhixing.facephoto.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.zhixing.facephoto.protocol.WorkerProtocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static cn.zhixing.facephoto.core.BatchModels.*;
import static cn.zhixing.facephoto.core.BatchStore.*;

public final class BatchService implements AutoCloseable {
    private static final Set<BatchState> CAN_START =
            Set.of(BatchState.READY, BatchState.CANCELLED, BatchState.PAUSED);
    private static final Set<BatchState> ACTIVE =
            Set.of(BatchState.CHECKING, BatchState.RUNNING, BatchState.CANCELLING);
    private static final Set<String> REQUIRED_CAPABILITIES =
            Set.of("faceCount", "boxes", "pose", "blur", "occlusion", "illumination");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final BatchStore store;
    private final WorkerFactory factory;
    private final AtomicReference<Run> active = new AtomicReference<>();

    public BatchService(BatchStore store, WorkerFactory factory) {
        this.store = Objects.requireNonNull(store);
        this.factory = Objects.requireNonNull(factory);
    }

    @FunctionalInterface
    public interface WorkerFactory {
        Worker open() throws IOException;
    }

    public interface Worker extends AutoCloseable {
        ObjectNode initialize() throws IOException;
        ObjectNode measure(Path file) throws IOException;
        @Override
        void close();

        default void abort() {
            close();
        }
    }

    public static final class WorkerFailure extends IOException {
        private final String code;

        public WorkerFailure(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public Batch select(Path selected) throws IOException {
        Path root = PhotoSource.root(selected);
        Batch latest = store.latest(root);
        return latest == null ? store.create(PhotoSource.scan(root)) : latest;
    }

    public Batch rescan(long batchId) throws IOException {
        Batch current = store.batch(batchId);
        if (ACTIVE.contains(current.state())) {
            throw new IOException("检测运行期间无法重新扫描");
        }
        return store.create(PhotoSource.scan(current.root()));
    }

    public Batch run(long batchId) throws IOException {
        Batch batch = store.batch(batchId);
        if (!CAN_START.contains(batch.state()) || batch.total() == 0) {
            return batch;
        }
        Run run = new Run(batch);
        if (!active.compareAndSet(null, run)) {
            throw new IOException("已有检测批次正在运行");
        }
        try {
            if (store.transition(batchId, CAN_START, BatchState.CHECKING, null)) {
                execute(run);
            }
            return store.batch(batchId);
        } finally {
            run.done.countDown();
            try {
                run.closeWorker();
            } finally {
                active.compareAndSet(run, null);
            }
        }
    }

    private void execute(Run run) throws IOException {
        long id = run.batch.id();
        try {
            if (!run.cancelled) {
                initialize(run);
            }
            if (!run.cancelled) {
                recheckCommitted(run);
            }
            if (!run.cancelled && store.transition(id, Set.of(BatchState.CHECKING), BatchState.RUNNING, null)) {
                processPending(run);
            }
            BatchState terminal = run.cancelled ? BatchState.CANCELLED : BatchState.COMPLETE;
            store.transition(id, ACTIVE, terminal, null);
        } catch (BatchFailure failure) {
            store.transition(id, ACTIVE, run.cancelled ? BatchState.CANCELLED : BatchState.PAUSED,
                    run.cancelled ? null : failure.code);
        } catch (IOException failure) {
            try {
                store.transition(id, ACTIVE, run.cancelled ? BatchState.CANCELLED : BatchState.PAUSED,
                        run.cancelled ? null : "DATABASE_WRITE_FAILED");
            } catch (IOException stateFailure) {
                failure.addSuppressed(stateFailure);
                throw failure;
            }
        }
    }

    private void initialize(Run run) throws IOException {
        Profile profile;
        try {
            Worker worker = factory.open();
            run.worker = worker;
            if (run.cancelled) {
                return;
            }
            JsonNode reply = checked(worker.initialize(), WorkerProtocol.INITIALIZE);
            if (!reply.path("ok").booleanValue()) {
                throw new BatchFailure(initializeError(reply.path("error")));
            }
            profile = parseProfile(reply.path("data"));
        } catch (BatchFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new BatchFailure(processCode(failure));
        }
        try {
            store.setProfile(run.batch.id(), profile);
        } catch (RuleMismatch mismatch) {
            throw new BatchFailure("RULES_INCOMPATIBLE");
        }
        run.profile = profile;
    }

    private static String initializeError(JsonNode error) {
        String code = error.path("code").asText("SDK_INIT_FAILED");
        JsonNode nativeCode = error.path("nativeCode");
        return "SDK_INIT_FAILED".equals(code) && nativeCode.isIntegralNumber()
                ? code + " (" + nativeCode.asText() + ")" : code;
    }

    private static Profile parseProfile(JsonNode data) throws BatchFailure {
        if (!data.path("authorized").asBoolean(false)) {
            throw new BatchFailure("SDK_UNAUTHORIZED");
        }
        if (!data.path("probes").path("singleFace").asBoolean(false)
                || !data.path("probes").path("noFace").asBoolean(false)) {
            throw new BatchFailure("SDK_PROBE_FAILED");
        }
        var capabilities = new HashSet<String>();
        data.path("capabilities").forEach(capability -> capabilities.add(capability.asText()));
        if (!capabilities.containsAll(REQUIRED_CAPABILITIES)) {
            throw new BatchFailure("SDK_CAPABILITIES_MISSING");
        }
        String fingerprint = data.path("measurementConfigFingerprint").asText();
        String engineId = data.path("engineId").asText();
        String engineVersion = data.path("engineVersion").asText();
        String semantics = data.path("metricSemanticsVersion").asText();
        if (!validHash(fingerprint) || engineId.isBlank() || engineVersion.isBlank() || semantics.isBlank()) {
            throw new BatchFailure("PROTOCOL_ERROR");
        }
        return new Profile(fingerprint.toLowerCase(Locale.ROOT), engineId, engineVersion, semantics, data);
    }

    private void recheckCommitted(Run run) throws IOException {
        List<Long> stale = new ArrayList<>();
        for (WorkItem item : store.items(run.batch.id(), true)) {
            if (run.cancelled) {
                return;
            }
            if (!isReusable(run, item)) {
                stale.add(item.id());
            }
        }
        store.invalidate(run.batch.id(), stale);
    }

    private static boolean isReusable(Run run, WorkItem item) {
        if (!item.reusable() || item.sha256() == null || !run.profile.fingerprint().equals(item.fingerprint())) {
            return false;
        }
        try {
            return item.sha256().equals(PhotoSource.sha256(PhotoSource.resolve(run.batch.root(), item.relativePath())));
        } catch (IOException error) {
            return false;
        }
    }

    private void processPending(Run run) throws IOException {
        long batchId = run.batch.id();
        boolean profileChanged;
        do {
            profileChanged = false;
            for (WorkItem item : store.items(batchId, false)) {
                String request = UUID.randomUUID().toString();
                if (run.cancelled || !store.claim(batchId, item.id(), request)) {
                    return;
                }
                Outcome outcome = resolveAndMeasure(run, item);
                if (outcome == null) {
                    profileChanged = !run.cancelled;
                    break;
                }
                if (!store.commit(batchId, item.id(), request, run.profile.fingerprint(), outcome) && !run.cancelled) {
                    throw new BatchFailure("STALE_MEASUREMENT");
                }
            }
        } while (profileChanged && !run.cancelled);
    }

    private Outcome resolveAndMeasure(Run run, WorkItem item) throws IOException {
        Path file;
        BasicFileAttributes attributes;
        try {
            file = PhotoSource.resolve(run.batch.root(), item.relativePath());
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return failure("read", "FILE_NOT_FOUND");
        } catch (IOException unavailable) {
            return failure("read", "FILE_READ_FAILED");
        }
        if (run.cancelled) {
            return null;
        }
        try {
            Outcome measured = measure(run, file, attributes);
            run.repeatedFault = null;
            return measured;
        } catch (BatchFailure failure) {
            throw failure;
        } catch (IOException failure) {
            return retryMeasure(run, file, attributes, processCode(failure));
        }
    }

    private Outcome retryMeasure(Run run, Path file, BasicFileAttributes attributes, String firstCode)
            throws IOException {
        if (run.cancelled || restart(run)) {
            return null;
        }
        try {
            Outcome measured = measure(run, file, attributes);
            run.repeatedFault = null;
            return measured;
        } catch (BatchFailure failure) {
            throw failure;
        } catch (IOException failure) {
            if (run.cancelled || restart(run)) {
                return null;
            }
            String code = processCode(failure);
            if (code.equals(run.repeatedFault)) {
                throw new BatchFailure(code);
            }
            run.repeatedFault = code;
            ObjectNode error = fileError("worker", code).put("firstCode", firstCode);
            return new Outcome(null, null, null, null, error, false);
        }
    }

    private boolean restart(Run run) throws IOException {
        String previous = run.profile.fingerprint();
        run.closeWorker();
        if (run.cancelled) {
            return false;
        }
        initialize(run);
        return !previous.equals(run.profile.fingerprint());
    }

    private static Outcome measure(Run run, Path file, BasicFileAttributes attributes) throws IOException {
        JsonNode reply = checked(run.worker.measure(file), WorkerProtocol.MEASURE);
        long bytes = attributes.size();
        long modified = attributes.lastModifiedTime().toMillis();
        if (reply.path("ok").booleanValue()) {
            JsonNode data = reply.path("data");
            String sha256 = data.path("sha256").asText();
            validateMeasurement(data, sha256);
            return new Outcome(sha256.toLowerCase(Locale.ROOT), bytes, modified, data, null, true);
        }
        JsonNode error = reply.path("error");
        String code = error.path("code").textValue();
        if ("batch".equals(error.path("scope").asText())) {
            throw new BatchFailure(code);
        }
        String sha256 = error.path("sha256").asText();
        String hash = validHash(sha256) ? sha256.toLowerCase(Locale.ROOT) : null;
        boolean reusable = hash != null && "JPEG_DECODE_FAILED".equals(code) && !error.path("retryable").asBoolean();
        return new Outcome(hash, bytes, modified, null, fileDiagnostic(error), reusable);
    }

    private static JsonNode checked(ObjectNode reply, String operation) throws IOException {
        if (reply == null || !operation.equals(reply.path("op").asText())) {
            throw new WorkerFailure("PROTOCOL_ERROR", null);
        }
        try {
            WorkerProtocol.validateResponse(reply);
        } catch (IOException error) {
            throw new WorkerFailure("PROTOCOL_ERROR", error);
        }
        return reply;
    }

    private static void validateMeasurement(JsonNode data, String sha256) throws WorkerFailure {
        JsonNode image = data.path("image");
        JsonNode count = data.path("faceCount");
        if (!validHash(sha256) || !count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0
                || !positiveInteger(image.path("sourceWidth")) || !positiveInteger(image.path("sourceHeight"))
                || !positiveInteger(image.path("width")) || !positiveInteger(image.path("height"))
                || !positiveInteger(image.path("orientation")) || image.path("orientation").intValue() > 8) {
            throw new WorkerFailure("PROTOCOL_ERROR", null);
        }
    }

    private static boolean positiveInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0;
    }

    private static boolean validHash(String hash) {
        return hash.matches("[a-fA-F0-9]{64}");
    }

    private static String processCode(IOException failure) {
        return failure instanceof WorkerFailure known ? known.code() : "WORKER_EXIT";
    }

    private static Outcome failure(String stage, String code) {
        return new Outcome(null, null, null, null, fileError(stage, code), false);
    }

    private static ObjectNode fileDiagnostic(JsonNode source) {
        ObjectNode error = fileError(source.path("stage").asText(), source.path("code").asText())
                .put("retryable", source.path("retryable").asBoolean());
        for (String field : List.of("nativeCode", "durationMs")) {
            if (source.path(field).isIntegralNumber()) {
                error.set(field, source.path(field));
            }
        }
        if (source.path("image").isObject()) {
            ObjectNode image = error.putObject("image");
            for (String field : List.of("sourceWidth", "sourceHeight", "width", "height", "orientation")) {
                if (positiveInteger(source.path("image").path(field))) {
                    image.set(field, source.path("image").path(field));
                }
            }
        }
        return error;
    }

    private static ObjectNode fileError(String stage, String code) {
        return JSON.objectNode().put("scope", "file").put("stage", stage).put("code", code).put("retryable", true);
    }

    public void cancel(long id) throws IOException {
        Run run = active.get();
        if (run == null || run.batch.id() != id) {
            return;
        }
        run.cancelled = true;
        try {
            store.transition(id, Set.of(BatchState.CHECKING, BatchState.RUNNING), BatchState.CANCELLING, null);
        } finally {
            if (!await(run, 2)) {
                Worker worker = run.worker;
                if (worker != null) {
                    worker.abort();
                }
            }
        }
    }

    private static boolean await(Run run, int seconds) throws IOException {
        try {
            return run.done.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("等待检测停止时中断", interrupted);
        }
    }

    public Snapshot snapshot(long batchId, Filter filter, int page, int pageSize) throws IOException {
        if (page < 0 || pageSize < 1) {
            throw new IllegalArgumentException("分页参数无效");
        }
        return store.snapshot(batchId, filter == null ? Filter.ALL : filter, page, pageSize);
    }

    public RuleSettings applyRules(long batchId, RuleSettings settings) throws IOException {
        return store.applyRules(batchId, Objects.requireNonNull(settings));
    }

    public void review(long revisionId, ReviewStatus status, String note) throws IOException {
        String value = note == null ? "" : note;
        if (value.codePointCount(0, value.length()) > 300) {
            throw new IllegalArgumentException("复核备注最多 300 字");
        }
        store.review(revisionId, status == null ? ReviewStatus.UNREVIEWED : status, value);
    }

    public ExportResult export(long batchId, Filter filter, ExportScope scope, ExportKind kind, Path target)
            throws IOException {
        Snapshot snapshot = snapshot(batchId, scope == ExportScope.ALL ? Filter.ALL : filter, 0, Integer.MAX_VALUE);
        return CsvExporter.write(snapshot, kind, target);
    }

    @Override
    public void close() throws IOException {
        Run run = active.get();
        if (run != null) {
            cancel(run.batch.id());
            if (!await(run, 5)) {
                throw new IOException("检测尚未停止，结果库仍在使用");
            }
        }
        store.close();
    }

    private static final class Run {
        private final Batch batch;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean cancelled;
        private volatile Worker worker;
        private Profile profile;
        private String repeatedFault;

        private Run(Batch batch) {
            this.batch = batch;
        }

        private void closeWorker() {
            Worker current = worker;
            worker = null;
            if (current != null) {
                current.close();
            }
        }
    }

    private static final class BatchFailure extends IOException {
        private final String code;

        private BatchFailure(String code) {
            super(code);
            this.code = code;
        }
    }
}
