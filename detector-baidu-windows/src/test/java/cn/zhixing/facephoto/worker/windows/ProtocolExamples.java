package cn.zhixing.facephoto.worker.windows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.zhixing.facephoto.protocol.WorkerProtocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class ProtocolExamples {
    private final Map<String, ObjectNode> requests = new HashMap<>();
    private final Map<String, ObjectNode> responses = new HashMap<>();

    ProtocolExamples() throws IOException {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/protocol-v1.jsonl"));
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ObjectNode frame = WorkerProtocol.parseLine(line);
                var frames = frame.has("ok") ? responses : requests;
                String id = frame.path("id").textValue();
                if (frames.putIfAbsent(id, frame) != null) {
                    throw new IOException("重复的协议样例：" + id);
                }
            }
        }
    }

    ObjectNode request(String id) {
        return get(requests, id);
    }

    ObjectNode response(String id) {
        return get(responses, id);
    }

    private static ObjectNode get(Map<String, ObjectNode> frames, String id) {
        return Objects.requireNonNull(frames.get(id), "协议样例不存在：" + id).deepCopy();
    }
}
