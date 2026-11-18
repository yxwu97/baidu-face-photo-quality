package cn.zhixing.facephoto.desktop.data;

import cn.zhixing.facephoto.core.BatchStore;
import cn.zhixing.facephoto.core.PhotoSource;
import cn.zhixing.facephoto.core.QualityRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static cn.zhixing.facephoto.core.BatchModels.*;

public final class SqliteBatchStore implements BatchStore {
    private static final int SCHEMA_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path database;
    private final FileChannel lockChannel;
    private final FileLock instanceLock;
    private final Connection writer;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final ReentrantLock writes = new ReentrantLock(true);
    private boolean closed;

    public SqliteBatchStore(Path database) throws IOException {
        this.database = database.toAbsolutePath().normalize();
        Path directory = this.database.getParent();
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            instanceLock = lockChannel.tryLock();
            if (instanceLock == null) {
                throw new IOException("程序已打开，结果库正在使用");
            }
        } catch (OverlappingFileLockException error) {
            lockChannel.close();
            throw new IOException("程序已打开，结果库正在使用", error);
        } catch (IOException error) {
            lockChannel.close();
            throw error;
        }
        Connection opened = null;
        try {
            boolean existing = Files.exists(this.database) && Files.size(this.database) > 0;
            opened = connect();
            writer = opened;
            initialize(existing);
        } catch (SQLException | IOException error) {
            try {
                if (opened != null) {
                    opened.close();
                }
            } catch (SQLException cleanup) {
                error.addSuppressed(cleanup);
            }
            try {
                releaseLock();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw new IOException("无法打开检测结果库", error);
        }
    }

    private void initialize(boolean existing) throws SQLException, IOException {
        update(writer, "PRAGMA journal_mode=WAL");
        int version = (int) scalar(writer, "PRAGMA user_version");
        if (version > SCHEMA_VERSION) {
            throw new IOException("检测结果库来自较新版本");
        }
        if (version < SCHEMA_VERSION) {
            if (existing) {
                Path backup = database.resolveSibling(database.getFileName() + ".before-v"
                        + SCHEMA_VERSION + "-" + UUID.randomUUID() + ".bak");
                update(writer, "VACUUM INTO ?", backup.toString());
            }
            migrate(writer);
        }
        write(db -> {
            update(db, """
                    UPDATE batch_item SET state='PENDING', active_request=NULL
                    WHERE state='PROCESSING'
                    """);
            update(db, """
                    UPDATE batch SET state='CANCELLED', error_code=NULL
                    WHERE state IN ('CHECKING','RUNNING','CANCELLING')
                    """);
            return null;
        });
    }

    private void migrate(Connection db) throws SQLException, IOException {
        try (var resource = SqliteBatchStore.class.getResourceAsStream("V001__results.sql")) {
            if (resource == null) {
                throw new IOException("缺少 SQLite 迁移脚本");
            }
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            transaction(db, false, conn -> {
                for (String statement : sql.split(";")) {
                    if (!statement.isBlank()) {
                        update(conn, statement);
                    }
                }
                long ruleId = insertRules(conn, RuleSettings.defaults(), null, null);
                update(conn, "INSERT INTO application_setting VALUES (1,?)", ruleId);
                update(conn, "PRAGMA user_version=" + SCHEMA_VERSION);
                return null;
            });
        }
    }

    @Override
    public Batch latest(Path root) throws IOException {
        return read(db -> {
            long id = scalar(db, """
                    SELECT IFNULL(MAX(b.batch_id),0) FROM batch b
                    JOIN source_root s ON s.root_id=b.root_id WHERE s.canonical_path=?
                    """, root.toString());
            return id == 0 ? null : SqliteReadModel.batch(db, id);
        });
    }

    @Override
    public Batch create(PhotoSource.Scan scan) throws IOException {
        return write(db -> {
            String path = scan.root().toString();
            long root = scalar(db, """
                    INSERT INTO source_root (canonical_path,display_path) VALUES (?,?)
                    ON CONFLICT(canonical_path) DO UPDATE SET display_path=excluded.display_path RETURNING root_id
                    """, path, path);
            long id = scalar(db, """
                    INSERT INTO batch (root_id,state,rule_set_id,total,unreadable_directories,created_at)
                    VALUES (?,'READY',(SELECT default_rule_set_id FROM application_setting WHERE setting_id=1),?,?,?)
                    RETURNING batch_id
                    """, root, scan.photos().size(), scan.unreadableDirectories(), now());
            insertItems(db, id, root, scan.photos());
            return SqliteReadModel.batch(db, id);
        });
    }

    private static void insertItems(Connection db, long batch, long root, List<PhotoSource.SourcePhoto> photos)
            throws SQLException {
        try (PreparedStatement photo = db.prepareStatement("""
                INSERT INTO photo (root_id,relative_path,filename,search_filename,capture_date,capture_time,
                                   capture_status,rec,live) VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(root_id,relative_path) DO UPDATE SET filename=excluded.filename RETURNING photo_id
                """);
             PreparedStatement item = db.prepareStatement("""
                INSERT INTO batch_item (batch_id,photo_id,sort_order,state) VALUES (?,?,?,'PENDING')
                """)) {
            int order = 0;
            for (PhotoSource.SourcePhoto entry : photos) {
                bind(photo, root, entry.relativePath(), entry.fileName(), entry.fileName().toLowerCase(Locale.ROOT),
                        entry.date(), entry.time(), entry.captureStatus(), entry.rec(), entry.live());
                try (ResultSet result = photo.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("无法记录照片清单");
                    }
                    bind(item, batch, result.getLong(1), order++);
                    item.addBatch();
                }
            }
            item.executeBatch();
        }
    }

    @Override
    public Batch batch(long id) throws IOException {
        return read(db -> SqliteReadModel.batch(db, id));
    }

    @Override
    public boolean transition(long id, Set<BatchState> from, BatchState next, String error) throws IOException {
        return write(db -> {
            Batch batch = SqliteReadModel.batch(db, id);
            if (!from.contains(batch.state())) {
                return false;
            }
            if (next == BatchState.COMPLETE && batch.total() != batch.committed()) {
                throw new SQLException("仍有未提交的照片");
            }
            if (next == BatchState.PAUSED || next == BatchState.CANCELLED) {
                update(db, """
                        UPDATE batch_item SET state='PENDING',active_request=NULL
                        WHERE batch_id=? AND state='PROCESSING'
                        """, id);
            }
            update(db, "UPDATE batch SET state=?,error_code=?,completed_at=? WHERE batch_id=?",
                    next.name(), error, next == BatchState.COMPLETE ? now() : null, id);
            return true;
        });
    }

    @Override
    public void setProfile(long id, Profile profile) throws IOException {
        write(db -> {
            assertCompatible(db, id, profile);
            insertProfile(db, profile);
            update(db, """
                    UPDATE batch_item SET state='PENDING',active_request=NULL,current_measurement_id=NULL
                    WHERE batch_id=? AND ((state='PROCESSING' AND EXISTS
                        (SELECT 1 FROM batch WHERE batch_id=? AND profile_fingerprint<>?)) OR current_measurement_id IN
                        (SELECT measurement_id FROM measurement WHERE fingerprint<>?))
                    """, id, id, profile.fingerprint(), profile.fingerprint());
            update(db, "UPDATE batch SET profile_fingerprint=? WHERE batch_id=?", profile.fingerprint(), id);
            return null;
        });
    }

    private static void assertCompatible(Connection db, long id, Profile profile) throws SQLException, IOException {
        if (scalar(db, """
                SELECT count(*) FROM batch b JOIN rule_set r ON r.rule_set_id=b.rule_set_id
                WHERE b.batch_id=? AND ((r.engine_id IS NOT NULL AND r.engine_id<>?) OR
                                        (r.metrics_version IS NOT NULL AND r.metrics_version<>?))
                """, id, profile.engineId(), profile.metricsVersion()) != 0) {
            throw new RuleMismatch();
        }
    }

    private static void insertProfile(Connection db, Profile profile) throws SQLException, IOException {
        JsonNode data = profile.details();
        update(db, """
                INSERT OR IGNORE INTO measurement_profile
                    (fingerprint,engine_id,engine_version,metrics_version,platform,architecture,
                     native_hash,model_hash,configuration_hash,adapter_version,decode_version,details_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, profile.fingerprint(), profile.engineId(), profile.engineVersion(), profile.metricsVersion(),
                text(data, "platform"), text(data, "architecture"), text(data, "nativeHash"), text(data, "modelHash"),
                text(data, "configurationHash"), text(data, "adapterVersion"), text(data, "decodeVersion"),
                JSON.writeValueAsString(data));
        if (scalar(db, """
                SELECT count(*) FROM measurement_profile
                WHERE fingerprint=? AND engine_id=? AND engine_version=? AND metrics_version=?
                """, profile.fingerprint(), profile.engineId(), profile.engineVersion(), profile.metricsVersion()) != 1) {
            throw new IOException("测量配置指纹与引擎信息不一致");
        }
    }

    @Override
    public List<WorkItem> items(long id, boolean committed) throws IOException {
        return read(db -> {
            var items = new ArrayList<WorkItem>();
            try (var query = prepare(db, """
                    SELECT i.item_id,p.relative_path,r.content_sha256,m.fingerprint,m.reusable
                    FROM batch_item i JOIN photo p ON p.photo_id=i.photo_id
                    LEFT JOIN measurement m ON m.measurement_id=i.current_measurement_id
                    LEFT JOIN photo_revision r ON r.revision_id=m.revision_id
                    WHERE i.batch_id=? AND i.state=? ORDER BY i.sort_order
                    """, id, committed ? "COMMITTED" : "PENDING");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    items.add(new WorkItem(rows.getLong(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getBoolean(5)));
                }
            }
            return List.copyOf(items);
        });
    }

    @Override
    public void invalidate(long id, List<Long> items) throws IOException {
        if (items.isEmpty()) {
            return;
        }
        write(db -> {
            try (var update = prepare(db, """
                    UPDATE batch_item SET state='PENDING',current_measurement_id=NULL
                    WHERE batch_id=? AND item_id=? AND state='COMMITTED'
                    """)) {
                for (long item : items) {
                    bind(update, id, item);
                    update.addBatch();
                }
                update.executeBatch();
            }
            return null;
        });
    }

    @Override
    public boolean claim(long id, long item, String request) throws IOException {
        return write(db -> update(db, """
                UPDATE batch_item SET state='PROCESSING',active_request=?
                WHERE batch_id=? AND item_id=? AND state='PENDING'
                  AND EXISTS (SELECT 1 FROM batch WHERE batch_id=? AND state='RUNNING')
                """, request, id, item, id) == 1);
    }

    @Override
    public boolean commit(long id, long item, String request, String fingerprint, Outcome outcome) throws IOException {
        return write(db -> {
            long photo = scalar(db, """
                    SELECT COALESCE(MAX(i.photo_id),0) FROM batch_item i JOIN batch b ON b.batch_id=i.batch_id
                    WHERE i.batch_id=? AND i.item_id=? AND i.state='PROCESSING' AND i.active_request=?
                      AND b.profile_fingerprint=? AND b.state IN ('RUNNING','CANCELLING')
                    """, id, item, request, fingerprint);
            if (photo == 0) {
                return false;
            }
            Long revision = revision(db, photo, outcome);
            long measurement = insertMeasurement(db, item, revision, fingerprint, outcome);
            var rule = SqliteReadModel.rule(db, id);
            insertDecision(db, measurement, rule.id(), rule.settings(), outcome.measurement(), outcome.error());
            int changed = update(db, """
                    UPDATE batch_item SET state='COMMITTED',active_request=NULL,current_measurement_id=?
                    WHERE item_id=? AND active_request=?
                    """, measurement, item, request);
            if (changed != 1) {
                throw new SQLException("检测请求已失效");
            }
            return true;
        });
    }

    private static Long revision(Connection db, long photo, Outcome outcome) throws SQLException {
        if (outcome.sha256() == null) {
            return null;
        }
        update(db, """
                INSERT OR IGNORE INTO photo_revision (photo_id,content_sha256,file_size,modified_ms) VALUES (?,?,?,?)
                """, photo, outcome.sha256(), outcome.bytes(), outcome.modified());
        return scalar(db, "SELECT revision_id FROM photo_revision WHERE photo_id=? AND content_sha256=?",
                photo, outcome.sha256());
    }

    private static long insertMeasurement(Connection db, long item, Long revision, String fingerprint, Outcome outcome)
            throws SQLException, IOException {
        JsonNode data = outcome.measurement() == null ? MissingNode.getInstance() : outcome.measurement();
        JsonNode error = outcome.error() == null ? MissingNode.getInstance() : outcome.error();
        JsonNode image = data.isMissingNode() ? error.path("image") : data.path("image");
        return scalar(db, """
                INSERT INTO measurement (item_id,revision_id,fingerprint,source_width,source_height,width,height,
                                         orientation,duration_ms,measurement_json,error_json,reusable)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING measurement_id
                """, item, revision, fingerprint, integer(image, "sourceWidth"), integer(image, "sourceHeight"),
                integer(image, "width"), integer(image, "height"), integer(image, "orientation"),
                integer(data.isMissingNode() ? error : data, "durationMs"),
                serialize(outcome.measurement()), serialize(outcome.error()), outcome.reusable() ? 1 : 0);
    }

    private static void insertDecision(Connection db, long measurement, long rule, RuleSettings settings,
                                       JsonNode data, JsonNode error) throws SQLException, IOException {
        var decision = error == null ? QualityRules.evaluate(data, settings) : QualityRules.failure(error);
        long id = scalar(db, """
                INSERT INTO decision (measurement_id,rule_set_id,status,decided_at)
                VALUES (?,?,?,?) RETURNING decision_id
                """, measurement, rule, decision.status().name(), now());
        int order = 0;
        for (Reason reason : decision.reasons()) {
            update(db, "INSERT INTO decision_reason VALUES (?,?,?,?)", id, reason.code(), order++,
                    JSON.writeValueAsString(reason.evidence()));
        }
    }

    @Override
    public Snapshot snapshot(long id, Filter filter, int page, int size) throws IOException {
        return read(db -> SqliteReadModel.snapshot(db, id, filter, page, size));
    }

    @Override
    public RuleSettings applyRules(long id, RuleSettings settings) throws IOException {
        return write(db -> {
            RuleSettings next = settings.withVersion("rule-" + UUID.randomUUID());
            long ruleId = insertApplicableRule(db, id, next);
            redecide(db, id, ruleId, next);
            update(db, "UPDATE batch SET rule_set_id=? WHERE batch_id=?", ruleId, id);
            update(db, "UPDATE application_setting SET default_rule_set_id=? WHERE setting_id=1", ruleId);
            return next;
        });
    }

    private static long insertApplicableRule(Connection db, long id, RuleSettings settings)
            throws SQLException, IOException {
        try (var query = prepare(db, """
                SELECT COALESCE(m.engine_id,r.engine_id),COALESCE(m.metrics_version,r.metrics_version)
                FROM batch b JOIN rule_set r ON r.rule_set_id=b.rule_set_id
                LEFT JOIN measurement_profile m ON m.fingerprint=b.profile_fingerprint WHERE b.batch_id=?
                """, id); var row = query.executeQuery()) {
            if (!row.next()) {
                throw new IOException("找不到检测批次");
            }
            return insertRules(db, settings, row.getString(1), row.getString(2));
        }
    }

    private static long insertRules(Connection db, RuleSettings settings, String engine, String metrics)
            throws SQLException, IOException {
        return scalar(db, """
                INSERT INTO rule_set(version,engine_id,metrics_version,rules_json,created_at) VALUES (?,?,?,?,?)
                RETURNING rule_set_id
                """, settings.version(), engine, metrics, JSON.writeValueAsString(settings), now());
    }

    private static void redecide(Connection db, long id, long rule, RuleSettings settings)
            throws SQLException, IOException {
        try (var query = prepare(db, """
                SELECT m.measurement_id,m.measurement_json,m.error_json
                FROM batch_item i JOIN measurement m ON m.measurement_id=i.current_measurement_id
                WHERE i.batch_id=? AND i.state='COMMITTED' ORDER BY i.sort_order
                """, id); var rows = query.executeQuery()) {
            while (rows.next()) {
                insertDecision(db, rows.getLong(1), rule, settings, parse(rows.getString(2)), parse(rows.getString(3)));
            }
        }
    }

    @Override
    public void review(long revision, ReviewStatus status, String note) throws IOException {
        write(db -> {
            if (status == ReviewStatus.UNREVIEWED && note.isEmpty()) {
                update(db, "DELETE FROM manual_review WHERE revision_id=?", revision);
            } else {
                update(db, """
                        INSERT INTO manual_review VALUES (?,?,?,?)
                        ON CONFLICT(revision_id) DO UPDATE SET conclusion=excluded.conclusion,note=excluded.note,
                            updated_at=excluded.updated_at
                        """, revision, status == ReviewStatus.UNREVIEWED ? null : status.name(), note, now());
            }
            return null;
        });
    }

    @Override
    public void close() throws IOException {
        lifecycle.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                try {
                    writer.close();
                } catch (SQLException error) {
                    throw new IOException("无法关闭检测结果库", error);
                } finally {
                    releaseLock();
                }
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private void releaseLock() throws IOException {
        try {
            instanceLock.release();
        } finally {
            lockChannel.close();
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try {
            update(connection, "PRAGMA busy_timeout=5000");
            update(connection, "PRAGMA foreign_keys=ON");
            update(connection, "PRAGMA synchronous=FULL");
            return connection;
        } catch (SQLException error) {
            try {
                connection.close();
            } catch (SQLException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private <T> T write(Work<T> job) throws IOException {
        writes.lock();
        try {
            return transact(false, job);
        } finally {
            writes.unlock();
        }
    }

    private <T> T read(Work<T> job) throws IOException {
        return transact(true, job);
    }

    private <T> T transact(boolean queryOnly, Work<T> job) throws IOException {
        lifecycle.readLock().lock();
        try {
            if (closed) {
                throw new IOException("结果库已关闭");
            }
            try {
                if (!queryOnly) {
                    return transaction(writer, false, job);
                }
                try (Connection connection = connect()) {
                    return transaction(connection, true, job);
                }
            } catch (SQLException failure) {
                throw new IOException("检测结果库操作失败", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private static <T> T transaction(Connection db, boolean queryOnly, Work<T> job) throws SQLException, IOException {
        if (queryOnly) {
            update(db, "PRAGMA query_only=ON");
        }
        db.setAutoCommit(false);
        try {
            T value = job.run(db);
            db.commit();
            return value;
        } catch (SQLException | IOException | RuntimeException error) {
            try {
                db.rollback();
            } catch (SQLException rollback) {
                error.addSuppressed(rollback);
                try {
                    db.close();
                } catch (SQLException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        } finally {
            if (!db.isClosed()) {
                db.setAutoCommit(true);
            }
        }
    }

    static PreparedStatement prepare(Connection db, String sql, Object... args) throws SQLException {
        var statement = db.prepareStatement(sql);
        try {
            bind(statement, args);
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int index = 0; index < args.length; index++) {
            statement.setObject(index + 1, args[index]);
        }
    }

    private static int update(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = prepare(db, sql, args)) {
            return statement.execute() ? 0 : statement.getUpdateCount();
        }
    }

    static long scalar(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = prepare(db, sql, args); var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("结果库查询缺少数据");
            }
            return result.getLong(1);
        }
    }

    static JsonNode parse(String value) throws IOException {
        return value == null ? null : JSON.readTree(value);
    }

    static RuleSettings parseRules(String value) throws IOException {
        return JSON.readValue(value, RuleSettings.class);
    }

    private static String serialize(JsonNode node) throws IOException {
        return node == null ? null : JSON.writeValueAsString(node);
    }

    static String text(JsonNode node, String field) {
        return node == null ? null : node.path(field).asText(null);
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }

    private static String now() {
        return Instant.now().toString();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException, IOException;
    }
}
