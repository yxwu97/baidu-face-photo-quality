package cn.zhixing.facephoto.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

public final class WorkerProtocol {
    public static final int VERSION = 1;
    public static final String CHANNEL = "face.worker";
    public static final String INITIALIZE = "initialize";
    public static final String MEASURE = "measure";
    public static final String SHUTDOWN = "shutdown";
    public static final String UNSUPPORTED_VERSION = "UNSUPPORTED_PROTOCOL_VERSION";

    private static final Set<String> OPERATIONS = Set.of(INITIALIZE, MEASURE, SHUTDOWN);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private WorkerProtocol() {
    }

    public static ObjectNode parseLine(String line) throws ProtocolException {
        try {
            JsonNode frame = JSON.readTree(line);
            require(frame != null && frame.isObject(), "协议行必须是 JSON 对象");
            return (ObjectNode) frame;
        } catch (JsonProcessingException e) {
            throw new ProtocolException("PROTOCOL_ERROR", "无法解析协议行");
        }
    }

    public static void writeLine(Writer output, ObjectNode frame) throws IOException {
        output.write(JSON.writeValueAsString(frame));
        output.write('\n');
        output.flush();
    }

    public static void validateRequest(ObjectNode frame) throws ProtocolException {
        validateHeader(frame);
        JsonNode args = frame.path("args");
        require(args.isObject(), "请求缺少 args 对象");
        switch (frame.path("op").textValue()) {
            case INITIALIZE:
                require(hasText(args, "configurationId"), "请求缺少运行配置标识");
                break;
            case MEASURE:
                require(hasText(args, "path"), "请求缺少图片路径");
                break;
            case SHUTDOWN:
                require(args.isEmpty(), "shutdown 不接收参数");
                break;
            default:
                throw new IllegalStateException("操作未注册");
        }
    }

    public static void validateResponse(ObjectNode frame) throws ProtocolException {
        validateHeader(frame);
        JsonNode ok = frame.path("ok");
        require(ok.isBoolean(), "响应缺少布尔值 ok");
        if (ok.booleanValue()) {
            require(frame.path("data").isObject() && !frame.has("error"), "成功响应需要 data 对象");
            return;
        }
        JsonNode error = frame.path("error");
        require(error.isObject() && !frame.has("data"), "失败响应需要 error 对象");
        String scope = error.path("scope").asText();
        require("file".equals(scope) || "batch".equals(scope), "错误范围必须是 file 或 batch");
        require(hasText(error, "stage") && hasText(error, "code"), "错误缺少阶段或代码");
        require(error.path("retryable").isBoolean(), "错误缺少布尔值 retryable");
        require(!error.has("nativeCode") || error.path("nativeCode").isIntegralNumber(),
                "nativeCode 必须是整数");
    }

    private static void validateHeader(ObjectNode frame) throws ProtocolException {
        require(CHANNEL.equals(frame.path("channel").asText()), "协议通道不匹配");
        require(hasText(frame, "id"), "缺少请求编号");
        require(OPERATIONS.contains(frame.path("op").asText()), "操作名无效");
        JsonNode version = frame.path("v");
        require(version.isIntegralNumber(), "协议版本必须是整数");
        if (!version.canConvertToInt() || version.intValue() != VERSION) {
            throw new ProtocolException(UNSUPPORTED_VERSION, "不支持该协议版本");
        }
    }

    private static boolean hasText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() && !value.textValue().isBlank();
    }

    private static void require(boolean valid, String message) throws ProtocolException {
        if (!valid) {
            throw new ProtocolException("PROTOCOL_ERROR", message);
        }
    }
}
