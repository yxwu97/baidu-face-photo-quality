package cn.zhixing.facephoto.desktop.data;

import cn.zhixing.facephoto.core.QualityRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static cn.zhixing.facephoto.core.BatchModels.*;
import static cn.zhixing.facephoto.desktop.data.SqliteBatchStore.*;

final class SqliteReadModel {
    private static final String COMMITTED = """
            FROM batch_item i JOIN batch b ON b.batch_id=i.batch_id
            JOIN photo p ON p.photo_id=i.photo_id
            JOIN measurement m ON m.measurement_id=i.current_measurement_id
            JOIN decision d ON d.measurement_id=m.measurement_id AND d.rule_set_id=b.rule_set_id
            """;
    private static final String IN_BATCH = " WHERE i.batch_id=? AND i.state='COMMITTED' ";
    private static final String DATE = " AND COALESCE(p.capture_date,'')=? ";
    private static final String ROWS = """
            SELECT i.item_id,p.relative_path,p.filename,p.capture_date,p.capture_time,p.capture_status,p.rec,p.live,
                   m.revision_id,m.measurement_json,m.error_json,m.fingerprint,d.status,r.content_sha256,
                   v.conclusion,v.note,e.engine_id,e.engine_version,
                   (SELECT json_group_array(json_object('code',a.code,'evidence',json(a.evidence_json))) FROM
                       (SELECT code,evidence_json FROM decision_reason
                        WHERE decision_id=d.decision_id ORDER BY reason_order) a) AS reasons
            """;

    private SqliteReadModel() {
    }

    record Rule(long id, RuleSettings settings) {
    }

    static Batch batch(Connection db, long id) throws SQLException, IOException {
        try (var query = prepare(db, """
                SELECT b.batch_id,s.canonical_path,b.state,b.total,b.unreadable_directories,b.error_code,
                       (SELECT count(*) FROM batch_item i WHERE i.batch_id=b.batch_id AND i.state='COMMITTED')
                FROM batch b JOIN source_root s ON s.root_id=b.root_id WHERE b.batch_id=?
                """, id); var row = query.executeQuery()) {
            if (!row.next()) {
                throw new IOException("找不到检测批次");
            }
            return new Batch(row.getLong(1), Path.of(row.getString(2)), BatchState.valueOf(row.getString(3)),
                    row.getInt(4), row.getInt(7), row.getInt(5), row.getString(6));
        }
    }

    static Rule rule(Connection db, long id) throws SQLException, IOException {
        try (var query = prepare(db, """
                SELECT r.rule_set_id,r.rules_json FROM batch b JOIN rule_set r ON r.rule_set_id=b.rule_set_id
                WHERE b.batch_id=?
                """, id); var row = query.executeQuery()) {
            if (!row.next()) {
                throw new IOException("找不到当前规则");
            }
            return new Rule(row.getLong(1), parseRules(row.getString(2)));
        }
    }

    static Snapshot snapshot(Connection db, long id, Filter filter, int page, int size)
            throws SQLException, IOException {
        Batch batch = batch(db, id);
        Map<String, Integer> dates = dates(db, id);
        Selection dateScope = selection(id, new Filter(filter.date(), null, null, null));
        Selection filterScope = selection(id, filter);
        return new Snapshot(batch, rule(db, id).settings(), dates, states(db, dateScope),
                reasons(db, dateScope), rows(db, filterScope, page, size),
                (int) scalar(db, "SELECT count(*) " + COMMITTED + filterScope.where(), filterScope.args()));
    }

    private static Map<String, Integer> dates(Connection db, long id) throws SQLException {
        var dates = new LinkedHashMap<String, Integer>();
        try (var query = prepare(db, """
                SELECT COALESCE(p.capture_date,'') AS day,count(*)
                FROM batch_item i JOIN photo p ON p.photo_id=i.photo_id
                WHERE i.batch_id=? GROUP BY day ORDER BY day='' ASC, day DESC
                """, id); var rows = query.executeQuery()) {
            while (rows.next()) {
                dates.put(rows.getString(1), rows.getInt(2));
            }
        }
        return Collections.unmodifiableMap(dates);
    }

    private static Map<Status, Integer> states(Connection db, Selection scope) throws SQLException {
        var counts = new EnumMap<Status, Integer>(Status.class);
        for (Status status : Status.values()) {
            counts.put(status, 0);
        }
        try (var query = prepare(db, "SELECT d.status,count(*) " + COMMITTED + scope.where() + " GROUP BY d.status",
                scope.args()); var rows = query.executeQuery()) {
            while (rows.next()) {
                counts.put(Status.valueOf(rows.getString(1)), rows.getInt(2));
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private static Map<String, Integer> reasons(Connection db, Selection scope) throws SQLException {
        String sql = "SELECT dr.code,count(*) " + COMMITTED
                + " JOIN decision_reason dr ON dr.decision_id=d.decision_id " + scope.where() + " GROUP BY dr.code";
        Map<String, Integer> counts = new HashMap<>();
        try (var query = prepare(db, sql, scope.args()); var rows = query.executeQuery()) {
            while (rows.next()) {
                counts.put(rows.getString(1), rows.getInt(2));
            }
        }
        var sorted = new LinkedHashMap<String, Integer>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> QualityRules.label(entry.getKey()), Comparator.naturalOrder()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    private static Selection selection(long id, Filter filter) {
        var clauses = new StringBuilder(IN_BATCH);
        List<Object> args = new ArrayList<>();
        args.add(id);
        if (filter.date() != null) {
            clauses.append(DATE);
            args.add(filter.date());
        }
        if (filter.status() != null) {
            clauses.append(" AND d.status=? ");
            args.add(filter.status().name());
        }
        if (filter.reason() != null && !filter.reason().isBlank()) {
            clauses.append(" AND EXISTS (SELECT 1 FROM decision_reason f WHERE f.decision_id=d.decision_id AND f.code=?) ");
            args.add(filter.reason());
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            clauses.append(" AND instr(p.search_filename,?)>0 ");
            args.add(filter.keyword().toLowerCase(Locale.ROOT));
        }
        return new Selection(clauses.toString(), args.toArray());
    }

    private static List<PhotoResult> rows(Connection db, Selection scope, int page, int size)
            throws SQLException, IOException {
        String sql = ROWS + COMMITTED + """
                JOIN measurement_profile e ON e.fingerprint=m.fingerprint
                LEFT JOIN photo_revision r ON r.revision_id=m.revision_id
                LEFT JOIN manual_review v ON v.revision_id=m.revision_id
                """ + scope.where() + " ORDER BY i.sort_order LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(List.of(scope.args()));
        args.add(size);
        args.add((long) page * size);
        List<PhotoResult> result = new ArrayList<>();
        try (var query = prepare(db, sql, args.toArray()); var rows = query.executeQuery()) {
            while (rows.next()) {
                result.add(row(rows));
            }
        }
        return List.copyOf(result);
    }

    private static PhotoResult row(ResultSet row) throws SQLException, IOException {
        JsonNode measurement = parse(row.getString("measurement_json"));
        JsonNode error = parse(row.getString("error_json"));
        if (measurement == null) {
            if (error != null && error.path("image").isObject()) {
                measurement = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .set("image", error.path("image"));
            } else {
                measurement = MissingNode.getInstance();
            }
        }
        List<Reason> reasons = new ArrayList<>();
        JsonNode reasonJson = parse(row.getString("reasons"));
        if (reasonJson != null) {
            for (JsonNode reason : reasonJson) {
                String code = reason.path("code").textValue();
                reasons.add(new Reason(code, QualityRules.label(code), reason.path("evidence")));
            }
        }
        String review = row.getString("conclusion");
        long revision = row.getLong("revision_id");
        Long revisionId = row.wasNull() ? null : revision;
        return new PhotoResult(row.getLong("item_id"), revisionId, row.getString("relative_path"),
                row.getString("filename"), row.getString("capture_date"), row.getString("capture_time"),
                row.getString("capture_status"), nullableInt(row, "rec"), nullableInt(row, "live"),
                Status.valueOf(row.getString("status")), List.copyOf(reasons), measurement,
                row.getString("content_sha256"), text(error, "stage"), text(error, "code"),
                review == null ? ReviewStatus.UNREVIEWED : ReviewStatus.valueOf(review), row.getString("note"),
                row.getString("engine_id"), row.getString("engine_version"), row.getString("fingerprint"));
    }

    private static Integer nullableInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private record Selection(String where, Object[] args) {
    }
}
