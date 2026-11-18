package cn.zhixing.facephoto.worker.windows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.zhixing.facephoto.protocol.ProtocolException;
import cn.zhixing.facephoto.protocol.WorkerProtocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

final class FakeWorker {
    enum Mode { NORMAL, MISSING_METRIC, FILE_ERROR, BATCH_ERROR, LATE, DUPLICATE, TIMEOUT, EXIT }

    private final Mode mode;
    private final ProtocolExamples examples = new ProtocolExamples();
    private boolean initialized;

    private FakeWorker(Mode mode) throws IOException {
        this.mode = mode;
    }

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 11 || !"amd64".equals(System.getProperty("os.arch"))) {
            throw new IllegalStateException("The test worker requires Java 11 x64");
        }
        var worker = new FakeWorker(args.length == 0 ? Mode.NORMAL : Mode.valueOf(args[0]));
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             var output = new OutputStreamWriter(System.out, StandardCharsets.UTF_8)) {
            worker.run(input, output);
        }
    }

    private void run(BufferedReader input, Writer output) throws IOException, InterruptedException {
        String line;
        while ((line = input.readLine()) != null) {
            ObjectNode request = WorkerProtocol.parseLine(line);
            ObjectNode response = responseFor(request);
            response.set("id", request.get("id"));
            WorkerProtocol.writeLine(output, response);
            if (mode == Mode.DUPLICATE && WorkerProtocol.MEASURE.equals(response.path("op").textValue())) {
                WorkerProtocol.writeLine(output, response);
            }
            if (WorkerProtocol.SHUTDOWN.equals(response.path("op").textValue())) {
                break;
            }
        }
    }

    private ObjectNode responseFor(ObjectNode request) throws IOException, InterruptedException {
        try {
            WorkerProtocol.validateRequest(request);
        } catch (ProtocolException e) {
            if (WorkerProtocol.UNSUPPORTED_VERSION.equals(e.code())) {
                return examples.response("bad-version").put("op", request.path("op").textValue());
            }
            throw e;
        }

        switch (request.path("op").textValue()) {
            case WorkerProtocol.INITIALIZE:
                initialized = mode != Mode.BATCH_ERROR;
                return examples.response(initialized ? "init-ok" : "init-error");
            case WorkerProtocol.MEASURE:
                if (!initialized) {
                    throw new IllegalStateException("initialize must succeed before measure");
                }
                return measure();
            case WorkerProtocol.SHUTDOWN:
                return examples.response("shutdown-ok");
            default:
                throw new IllegalStateException("Unknown operation");
        }
    }

    private ObjectNode measure() throws InterruptedException {
        switch (mode) {
            case MISSING_METRIC:
                return examples.response("measure-partial");
            case FILE_ERROR:
                return examples.response("measure-file-error");
            case LATE:
                Thread.sleep(600);
                break;
            case TIMEOUT:
                Thread.sleep(60_000);
                break;
            case EXIT:
                System.err.println("TEST_WORKER_EXIT");
                System.exit(70);
                break;
            default:
                break;
        }
        return examples.response("measure-ok");
    }
}
