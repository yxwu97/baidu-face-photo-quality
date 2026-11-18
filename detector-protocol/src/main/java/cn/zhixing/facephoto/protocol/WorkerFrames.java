package cn.zhixing.facephoto.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class WorkerFrames {
    private WorkerFrames() {}

    public static ObjectNode request(String id, String operation, ObjectNode args) {
        return envelope(id, operation).set("args", args);
    }

    public static ObjectNode success(String id, String operation, ObjectNode data) {
        return envelope(id, operation).put("ok", true).set("data", data);
    }

    public static ObjectNode failure(String id, String operation, ObjectNode error) {
        return envelope(id, operation).put("ok", false).set("error", error);
    }

    public static ObjectNode error(String scope, String stage, String code, boolean retryable) {
        return object().put("scope", scope).put("stage", stage).put("code", code)
                .put("retryable", retryable);
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode envelope(String id, String operation) {
        return object().put("v", WorkerProtocol.VERSION).put("channel", WorkerProtocol.CHANNEL)
                .put("id", id).put("op", operation);
    }
}
