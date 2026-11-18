package cn.zhixing.facephoto.desktop.data;

import cn.zhixing.facephoto.core.BatchService;
import cn.zhixing.facephoto.core.BatchStore;
import cn.zhixing.facephoto.core.PhotoSource;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.zhixing.facephoto.core.BatchModels.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class BatchServiceTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String PROFILE_A = "a".repeat(64);
    private static final String PROFILE_B = "b".repeat(64);
    @TempDir
    Path temp;

    @Test
    void scansQueriesReviewsRejudgesAndExportsCommittedResults() throws Exception {
        Path root = temp.resolve("抓拍");
        Path first = file(root, "2026-11-17/2026-02-30/090402_未识别_rec0_live96.JPEG");
        file(root, "2026-11-17/240000_未识别_recx_live0.jpg");
        file(root, "=2+3.jpg");
        file(root, "ignored.txt");
        try (var service = new BatchService(new SqliteBatchStore(database()), () -> new Stub(PROFILE_A) {
            @Override
            public ObjectNode measure(Path path) throws IOException {
                if (path.getFileName().toString().startsWith("240000")) {
                    ObjectNode data = measurement(path);
                    ((ObjectNode) data.path("occlusion")).put("leftEye", 0.61).put("rightEye", 0.62);
                    return success("measure", data);
                }
                if (path.getFileName().toString().startsWith("=")) {
                    ObjectNode frame = error("measure", "file", "decode", "JPEG_DECODE_FAILED");
                    ((ObjectNode) frame.get("error")).put("sha256", PhotoSource.sha256(path));
                    return frame;
                }
                return super.measure(path);
            }
        })) {
            Batch ready = service.select(root);
            assertEquals(3, ready.total());
            Snapshot empty = service.snapshot(ready.id(), Filter.ALL, 0, 10);
            assertEquals(2, empty.dates().get("2026-11-17"));
            assertEquals(1, empty.dates().get(""));
            assertEquals(0, empty.filteredCount());
            Batch complete = service.run(ready.id());
            assertEquals(BatchState.COMPLETE, complete.state(), complete.toString());
            Snapshot result = service.snapshot(ready.id(), Filter.ALL, 0, 10);
            var good = result.rows().getFirst();
            assertEquals("09:04:02", good.time());
            assertEquals("2026-11-17", good.date());
            assertEquals(0, good.rec());
            assertEquals(Status.PASS, good.status());
            assertEquals(PhotoSource.sha256(first), good.sha256());
            var bad = result.rows().get(1);
            assertNull(bad.time());
            assertNull(bad.rec());
            assertEquals(0, bad.live());
            assertEquals(Status.FAIL, bad.status());
            assertEquals(1, result.reasonCounts().get("OCCLUSION_LIMIT"));
            assertEquals(2, bad.reasons().getFirst().evidence().size());
            assertEquals(Status.ERROR, result.rows().getLast().status());
            Filter onlyPass = new Filter("2026-11-17", Status.PASS, null, "REC0");
            assertEquals(1, service.snapshot(ready.id(), onlyPass, 0, 1).filteredCount());
            assertEquals(1, service.snapshot(ready.id(), onlyPass, 0, 1).statusCounts().get(Status.FAIL));

            String review = "=2+2,\"备注\"\r\n下一行";
            service.review(good.revisionId(), ReviewStatus.PASS, review);
            assertThrows(IllegalArgumentException.class,
                    () -> service.review(good.revisionId(), ReviewStatus.PASS, "😀".repeat(301)));
            var allCsv = service.export(ready.id(), onlyPass, ExportScope.ALL, ExportKind.DETAIL, temp.resolve("all.csv"));
            assertEquals(3, allCsv.count());
            String csv = Files.readString(allCsv.file(), StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("\uFEFF相对路径"));
            assertTrue(csv.contains("\r\n'=2+3.jpg"));
            assertTrue(csv.contains("\"'=2+2,\"\"备注\"\"\r\n下一行\""));
            assertTrue(csv.contains(",未识别,0,96,合格,"));
            Path dates = service.export(ready.id(), onlyPass, ExportScope.FILTERED, ExportKind.DATE,
                    temp.resolve("dates.csv")).file();
            assertTrue(Files.readString(dates).contains("\r\n2026-11-17,1,1,0,0,0\r\n"));
            Path reasonCsv = service.export(ready.id(), Filter.ALL, ExportScope.ALL, ExportKind.DATE_REASON,
                    temp.resolve("reasons.csv")).file();
            assertTrue(Files.readString(reasonCsv).contains("\r\n2026-11-17,局部遮挡,1\r\n"));
            Path occupied = Files.createDirectory(temp.resolve("blocked.csv"));
            Path sentinel = Files.writeString(occupied.resolve("keep"), "keep");
            assertThrows(IOException.class, () -> service.export(ready.id(), Filter.ALL, ExportScope.ALL,
                    ExportKind.DETAIL, occupied));
            assertEquals("keep", Files.readString(sentinel));
            assertThrows(IOException.class, () -> service.export(ready.id(), Filter.ALL, ExportScope.ALL,
                    ExportKind.DETAIL, first));
            assertEquals(good.sha256(), PhotoSource.sha256(first));

            RuleSettings next = service.applyRules(ready.id(), stricterRules());
            Snapshot rejudged = service.snapshot(ready.id(), Filter.ALL, 0, 10);
            assertEquals(next.version(), rejudged.rules().version());
            assertEquals(Status.FAIL, rejudged.rows().getFirst().status());
            assertEquals(ReviewStatus.PASS, rejudged.rows().getFirst().reviewStatus());
            assertEquals(review, rejudged.rows().getFirst().reviewNote());
            service.review(good.revisionId(), ReviewStatus.UNREVIEWED, "");
            assertEquals(ReviewStatus.UNREVIEWED,
                    service.snapshot(ready.id(), Filter.ALL, 0, 10).rows().getFirst().reviewStatus());
        }
    }

    @Test
    void resumesAfterReplacementAndDeletionAndMeasuresNewListingOnlyOnRescan() throws Exception {
        Path root = temp.resolve("photos");
        Path a = file(root, "a.jpg");
        Path b = file(root, "b.jpg");
        file(root, "c.jpg");
        file(root, "d.jpg");
        var attempt = new AtomicInteger();
        var measured = new ArrayList<String>();
        try (var service = new BatchService(new SqliteBatchStore(database()), () -> {
            int round = attempt.incrementAndGet();
            return new Stub(round < 2 ? PROFILE_A : PROFILE_B) {
                @Override
                public ObjectNode measure(Path path) throws IOException {
                    String name = path.getFileName().toString();
                    if (round == 1 && name.equals("c.jpg")) {
                        return error("measure", "batch", "measure", "TEST_PAUSE");
                    }
                    measured.add(name);
                    return super.measure(path);
                }
            };
        })) {
            Batch ready = service.select(root);
            assertEquals(BatchState.PAUSED, service.run(ready.id()).state());
            var before = service.snapshot(ready.id(), Filter.ALL, 0, 10);
            service.review(before.rows().getFirst().revisionId(), ReviewStatus.PASS, "原版本");
            Files.writeString(a, "replacement");
            Files.delete(b);
            file(root, "added.jpeg");
            measured.clear();
            assertEquals(BatchState.COMPLETE, service.run(ready.id()).state());
            var after = service.snapshot(ready.id(), Filter.ALL, 0, 10);
            assertEquals(List.of("a.jpg", "c.jpg", "d.jpg"), measured);
            assertNotEquals(before.rows().getFirst().revisionId(), after.rows().getFirst().revisionId());
            assertEquals(ReviewStatus.UNREVIEWED, after.rows().getFirst().reviewStatus());
            assertEquals("FILE_NOT_FOUND", after.rows().get(1).failureCode());
            assertEquals(PROFILE_B, after.rows().getFirst().profileFingerprint());
            assertEquals(4, after.batch().total());
            Batch rescanned = service.rescan(ready.id());
            assertNotEquals(ready.id(), rescanned.id());
            assertEquals(4, rescanned.total());
            assertEquals(rescanned.id(), service.select(root).id());
            measured.clear();
            assertEquals(BatchState.COMPLETE, service.run(rescanned.id()).state());
            assertEquals(List.of("a.jpg", "added.jpeg", "c.jpg", "d.jpg"), measured);
        }
    }

    @Test
    void recoveryKeepsCommittedRevisionAndRejectsStaleResponses() throws Exception {
        Path root = temp.resolve("photos");
        Path first = file(root, "a.jpg");
        file(root, "b.jpg");
        long batchId;
        long interruptedItem;
        try (var store = new SqliteBatchStore(database())) {
            assertThrows(IOException.class, () -> new SqliteBatchStore(database()));
            var batch = store.create(PhotoSource.scan(root));
            batchId = batch.id();
            store.transition(batchId, Set.of(BatchState.READY), BatchState.CHECKING, null);
            store.setProfile(batchId, new BatchStore.Profile(PROFILE_A, "test-double", "1", "metrics-1",
                    initializeData(PROFILE_A)));
            store.transition(batchId, Set.of(BatchState.CHECKING), BatchState.RUNNING, null);
            var items = store.items(batchId, false);
            long committedItem = items.getFirst().id();
            interruptedItem = items.getLast().id();
            assertTrue(store.claim(batchId, committedItem, "first-request"));
            var outcome = new BatchStore.Outcome(PhotoSource.sha256(first), Files.size(first), 0L,
                    measurement(first), null, true);
            assertTrue(store.commit(batchId, committedItem, "first-request", PROFILE_A, outcome));
            assertFalse(store.commit(batchId, committedItem, "first-request", PROFILE_A, outcome));
            assertTrue(store.claim(batchId, interruptedItem, "interrupted-request"));
        }
        var measured = new ArrayList<String>();
        try (var store = new SqliteBatchStore(database());
             var service = new BatchService(store, () -> new Stub(PROFILE_A) {
                 @Override
                 public ObjectNode measure(Path file) throws IOException {
                     measured.add(file.getFileName().toString());
                     return super.measure(file);
                 }
             })) {
            assertEquals(BatchState.CANCELLED, store.batch(batchId).state());
            assertEquals(1, store.batch(batchId).committed());
            assertFalse(store.commit(batchId, interruptedItem, "interrupted-request", PROFILE_A,
                    new BatchStore.Outcome(null, null, null, null, JSON.objectNode(), false)));
            assertEquals(BatchState.COMPLETE, service.run(batchId).state());
            assertEquals(List.of("b.jpg"), measured);
        }
    }

    @Test
    void handlesDatabaseRollbackAndRecoveryWithoutPartialResult() throws Exception {
        Path root = temp.resolve("photos");
        file(root, "a.jpg");
        try (var service = new BatchService(new SqliteBatchStore(database()), () -> new Stub(PROFILE_A))) {
            var ready = service.select(root);
            executeSql("""
                    CREATE TRIGGER fail_decision BEFORE INSERT ON decision BEGIN
                        SELECT RAISE(ABORT,'injected write failure');
                    END
                    """);
            var paused = service.run(ready.id());
            assertEquals(BatchState.PAUSED, paused.state());
            assertEquals("DATABASE_WRITE_FAILED", paused.errorCode());
            assertEquals(0, count("measurement"));
            assertEquals(0, count("photo_revision"));
            assertEquals(0, service.snapshot(ready.id(), Filter.ALL, 0, 10).filteredCount());
            executeSql("DROP TRIGGER fail_decision");
            assertEquals(BatchState.COMPLETE, service.run(ready.id()).state());
            assertEquals(1, count("measurement"));
        }
    }

    @Test
    void rejudgesDuringDetectionAndCommitsNextPhotoWithNewRules() throws Exception {
        Path root = temp.resolve("photos");
        file(root, "a.jpg");
        file(root, "b.jpg");
        var secondStarted = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        try (var service = new BatchService(new SqliteBatchStore(database()), () -> new Stub(PROFILE_A) {
            @Override
            public ObjectNode measure(Path file) throws IOException {
                if (file.getFileName().toString().equals("b.jpg")) {
                    secondStarted.countDown();
                    await(releaseSecond);
                }
                return super.measure(file);
            }
        }); var pool = Executors.newSingleThreadExecutor()) {
            var ready = service.select(root);
            var result = pool.submit(() -> service.run(ready.id()));
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertEquals(1, service.snapshot(ready.id(), Filter.ALL, 0, 10).batch().committed());
                service.applyRules(ready.id(), stricterRules());
                assertEquals(Status.FAIL, service.snapshot(ready.id(), Filter.ALL, 0, 10).rows().getFirst().status());
            } finally {
                releaseSecond.countDown();
            }
            assertEquals(BatchState.COMPLETE, result.get(5, TimeUnit.SECONDS).state());
            assertEquals(2, service.snapshot(ready.id(), Filter.ALL, 0, 10).statusCounts().get(Status.FAIL));
        }
    }

    @Test
    void retriesProcessFaultThenPausesOnConsecutiveFailedFiles() throws Exception {
        Path root = temp.resolve("photos");
        file(root, "a.jpg");
        file(root, "b.jpg");
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger attempted = new AtomicInteger();
        try (var service = new BatchService(new SqliteBatchStore(database()), () -> {
            opened.incrementAndGet();
            return new Stub(PROFILE_A) {
                @Override
                public ObjectNode measure(Path path) throws IOException {
                    attempted.incrementAndGet();
                    throw new BatchService.WorkerFailure("WORKER_EXIT", null);
                }
            };
        })) {
            long id = service.select(root).id();
            var pause = service.run(id);
            assertEquals(BatchState.PAUSED, pause.state());
            assertEquals(1, pause.committed());
            assertEquals("WORKER_EXIT", pause.errorCode());
            assertEquals(4, attempted.get());
            assertEquals(5, opened.get());
        }
    }

    @Test
    void cancelledCallIsReclaimedAndCanResume() throws Exception {
        Path root = temp.resolve("photos");
        file(root, "a.jpg");
        var started = new CountDownLatch(1);
        var abort = new CountDownLatch(1);
        var opens = new AtomicInteger();
        try (var service = new BatchService(new SqliteBatchStore(database()), () -> {
            if (opens.incrementAndGet() > 1) {
                return new Stub(PROFILE_A);
            }
            return new Stub(PROFILE_A) {
                @Override
                public ObjectNode measure(Path path) throws IOException {
                    started.countDown();
                    await(abort);
                    throw new BatchService.WorkerFailure("WORKER_EXIT", null);
                }

                @Override
                public void abort() {
                    abort.countDown();
                }
            };
        }); var pool = Executors.newSingleThreadExecutor()) {
            long id = service.select(root).id();
            var result = pool.submit(() -> service.run(id));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                service.cancel(id);
                assertTrue(abort.await(2, TimeUnit.SECONDS));
            } finally {
                abort.countDown();
            }
            Batch cancelled = result.get(5, TimeUnit.SECONDS);
            assertEquals(BatchState.CANCELLED, cancelled.state());
            assertEquals(0, cancelled.committed());
            assertEquals(BatchState.COMPLETE, service.run(id).state());
        }
    }

    @Test
    void initializationFailureKeepsHistoryAccessible() throws Exception {
        Path root = temp.resolve("photos");
        file(root, "a.jpg");
        try (var service = new BatchService(new SqliteBatchStore(database()), () -> new Stub(PROFILE_A) {
            @Override
            public ObjectNode initialize() {
                ObjectNode frame = error("initialize", "batch", "initialize", "SDK_INIT_FAILED");
                ((ObjectNode) frame.get("error")).put("nativeCode", -1022);
                return frame;
            }
        })) {
            long id = service.select(root).id();
            assertEquals("SDK_INIT_FAILED (-1022)", service.run(id).errorCode());
            assertEquals(BatchState.PAUSED, service.snapshot(id, Filter.ALL, 0, 10).batch().state());
        }
    }

    @Test
    void createsConsistentBackupBeforeMigratingAnExistingDatabase() throws Exception {
        Files.createDirectories(database().getParent());
        executeSql("CREATE TABLE legacy(version TEXT)");
        executeSql("INSERT INTO legacy VALUES ('previous')");
        try (var store = new SqliteBatchStore(database());
             var siblings = Files.list(database().getParent())) {
            Path backup = siblings.filter(file -> file.getFileName().toString().endsWith(".bak"))
                    .findFirst().orElseThrow();
            try (var saved = DriverManager.getConnection("jdbc:sqlite:" + backup);
                 var statement = saved.createStatement();
                 var rows = statement.executeQuery("SELECT version FROM legacy")) {
                assertTrue(rows.next());
                assertEquals("previous", rows.getString(1));
            }
            Path root = Files.createDirectory(temp.resolve("migrated"));
            assertEquals(0, store.create(PhotoSource.scan(root)).total());
        }
    }

    private Path database() {
        return temp.resolve("data/results.db");
    }

    private void executeSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static Path file(Path root, String name) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, name);
    }

    private static RuleSettings stricterRules() {
        return new RuleSettings("suggestion", 100, 100, 15, .59, .60, .60, false, 0, false);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Test stub timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }

    private static ObjectNode header(String operation, boolean ok) {
        return JSON.objectNode().put("v", 1).put("channel", "face.worker").put("id", "test")
                .put("op", operation).put("ok", ok);
    }

    private static ObjectNode success(String operation, ObjectNode data) {
        return header(operation, true).set("data", data);
    }

    private static ObjectNode error(String operation, String scope, String stage, String code) {
        return header(operation, false).set("error", JSON.objectNode().put("scope", scope)
                .put("stage", stage).put("code", code).put("retryable", false));
    }

    private static ObjectNode initializeData(String fingerprint) {
        ObjectNode data = JSON.objectNode().put("engineId", "test-double").put("engineVersion", "1")
                .put("metricSemanticsVersion", "metrics-1").put("measurementConfigFingerprint", fingerprint)
                .put("authorized", true);
        data.putObject("probes").put("singleFace", true).put("noFace", true);
        var capabilities = data.putArray("capabilities");
        List.of("faceCount", "boxes", "pose", "blur", "occlusion", "illumination").forEach(capabilities::add);
        return data;
    }

    private static ObjectNode measurement(Path file) throws IOException {
        ObjectNode data = JSON.objectNode().put("sha256", PhotoSource.sha256(file)).put("faceCount", 1).put("blur", .60);
        data.putObject("image").put("sourceWidth", 200).put("sourceHeight", 200)
                .put("width", 200).put("height", 200).put("orientation", 1);
        data.putArray("boxes").addObject().put("x", 5).put("y", 10).put("width", 100).put("height", 100);
        data.putObject("pose").put("yaw", -15).put("pitch", 0).put("roll", 0);
        ObjectNode occlusion = data.putObject("occlusion");
        List.of("leftEye", "rightEye", "nose", "mouth", "leftCheek", "rightCheek", "chin")
                .forEach(part -> occlusion.put(part, .60));
        return data;
    }

    private static class Stub implements BatchService.Worker {
        private final String fingerprint;

        Stub(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        @Override
        public ObjectNode initialize() {
            return success("initialize", initializeData(fingerprint));
        }

        @Override
        public ObjectNode measure(Path file) throws IOException {
            return success("measure", measurement(file));
        }

        @Override
        public void close() {
        }
    }
}
