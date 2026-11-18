package cn.zhixing.facephoto.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static cn.zhixing.facephoto.core.BatchModels.*;

public interface BatchStore extends AutoCloseable {
    final class RuleMismatch extends IOException {
        public RuleMismatch() {
            super("当前规则与检测引擎或测量语义不匹配");
        }
    }

    record Profile(String fingerprint, String engineId, String engineVersion, String metricsVersion, JsonNode details) {
    }

    record WorkItem(long id, String relativePath, String sha256, String fingerprint, boolean reusable) {
    }

    record Outcome(String sha256, Long bytes, Long modified, JsonNode measurement, JsonNode error, boolean reusable) {
    }

    Batch latest(Path root) throws IOException;

    Batch create(PhotoSource.Scan scan) throws IOException;

    Batch batch(long batchId) throws IOException;

    boolean transition(long batchId, Set<BatchState> from, BatchState next, String error) throws IOException;

    void setProfile(long batchId, Profile profile) throws IOException;

    List<WorkItem> items(long batchId, boolean committed) throws IOException;

    void invalidate(long batchId, List<Long> items) throws IOException;

    boolean claim(long batchId, long itemId, String request) throws IOException;

    boolean commit(long batchId, long itemId, String request, String fingerprint, Outcome outcome) throws IOException;

    Snapshot snapshot(long batchId, Filter filter, int page, int pageSize) throws IOException;

    RuleSettings applyRules(long batchId, RuleSettings settings) throws IOException;

    void review(long revisionId, ReviewStatus status, String note) throws IOException;

    @Override
    void close() throws IOException;
}
