package cn.zhixing.facephoto.worker.windows;

import cn.zhixing.facephoto.protocol.ProtocolException;
import cn.zhixing.facephoto.protocol.WorkerFrames;
import cn.zhixing.facephoto.protocol.WorkerProtocol;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class WindowsWorker {
    private BaiduSdk sdk;
    private boolean ready;

    public static void main(String[] args) throws IOException {
        try (BufferedWriter protocol = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8));
             BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.setOut(System.err);
            new WindowsWorker().run(input, protocol);
        }
    }

    private void run(BufferedReader input, BufferedWriter output) throws IOException {
        try {
            for (String line; (line = input.readLine()) != null;) {
                ObjectNode request;
                try {
                    request = WorkerProtocol.parseLine(line);
                } catch (ProtocolException e) {
                    System.err.println("worker protocol malformed-request");
                    continue;
                }
                ObjectNode response = respond(request);
                WorkerProtocol.writeLine(output, response);
                if (WorkerProtocol.SHUTDOWN.equals(response.path("op").asText())
                        && response.path("ok").asBoolean()) break;
            }
        } finally {
            try {
                release();
            } catch (ReflectiveOperationException | LinkageError e) {
                System.err.println("worker shutdown native-release-failed");
            }
        }
    }

    private ObjectNode respond(ObjectNode request) {
        String id = request.path("id").asText();
        String op = request.path("op").asText();
        try {
            WorkerProtocol.validateRequest(request);
            ObjectNode data = dispatch(op, request);
            return WorkerFrames.success(id, op, data);
        } catch (ProtocolException e) {
            return failure(id, op, "batch", "protocol", e.code(), false);
        } catch (BaiduSdk.SdkFailure e) {
            ObjectNode error = e.error;
            if (WorkerProtocol.INITIALIZE.equals(op) && "file".equals(error.path("scope").asText())) {
                return failure(id, op, "batch", "probe", "SDK_PROBE_FAILED", true);
            }
            return WorkerFrames.failure(id, op, error);
        } catch (LinkageError | ClassNotFoundException e) {
            System.err.println("worker " + id + " native-dependency-unavailable");
            return failure(id, op, "batch", op, "SDK_DEPENDENCY_UNAVAILABLE", true);
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            System.err.println("worker " + id + " " + op + " " + e.getClass().getSimpleName());
            // A native call failed outside its documented response contract. Force parent recovery.
            if (WorkerProtocol.MEASURE.equals(op)) throw new IllegalStateException("SDK invocation failed");
            return failure(id, op, "batch", op, "SDK_CALL_FAILED", true);
        }
    }

    private ObjectNode dispatch(String op, ObjectNode request)
            throws ReflectiveOperationException, IOException, BaiduSdk.SdkFailure {
        switch (op) {
            case WorkerProtocol.INITIALIZE:
                release();
                sdk = new BaiduSdk(Path.of("").toAbsolutePath().normalize());
                ObjectNode data = sdk.initialize();
                ready = true;
                return data;
            case WorkerProtocol.MEASURE:
                if (!ready) {
                    throw new BaiduSdk.SdkFailure(WorkerFrames.error(
                            "batch", "measure", "ENGINE_UNAVAILABLE", true));
                }
                Path path;
                try {
                    path = Path.of(request.path("args").path("path").asText());
                    if (!path.isAbsolute()) throw new InvalidPathException("", "absolute path required");
                } catch (InvalidPathException e) {
                    throw new BaiduSdk.SdkFailure(WorkerFrames.error(
                            "file", "read", "INVALID_PHOTO_PATH", false));
                }
                return sdk.measure(path);
            case WorkerProtocol.SHUTDOWN:
                release();
                return WorkerFrames.object().put("released", true);
            default:
                throw new IllegalStateException("unsupported worker operation");
        }
    }

    private void release() throws ReflectiveOperationException {
        ready = false;
        if (sdk != null) {
            try {
                sdk.close();
            } finally {
                sdk = null;
            }
        }
    }

    private static ObjectNode failure(String id, String op, String scope, String stage,
                                      String code, boolean retryable) {
        return WorkerFrames.failure(id, op, WorkerFrames.error(scope, stage, code, retryable));
    }
}
