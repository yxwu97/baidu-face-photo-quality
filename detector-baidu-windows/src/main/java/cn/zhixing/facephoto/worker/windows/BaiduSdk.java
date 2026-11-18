package cn.zhixing.facephoto.worker.windows;

import cn.zhixing.facephoto.protocol.JpegExif;
import cn.zhixing.facephoto.protocol.WorkerFrames;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class BaiduSdk implements AutoCloseable {
    private static final List<String> CAPABILITIES =
            List.of("faceCount", "boxes", "pose", "blur", "occlusion", "illumination");
    private static final String[] POSE_FIELDS = {"yaw", "pitch", "roll"};
    private static final String[] OCCLUSION_FIELDS =
            {"leftEye", "rightEye", "nose", "mouth", "leftCheek", "rightCheek", "chin"};
    private static final String SEMANTICS = "baidu-win84-1";
    private static final int COLOR_WITHOUT_ORIENTATION = 1 | 128;

    private final Class<?> faceType;
    private final Class<?> mat;
    private final Class<?> byteMat;
    private final Class<?> imgcodecs;
    private final Class<?> core;
    private final Object engine;
    private final Path sdkRoot;
    private boolean initialized;
    private boolean nullMeansNoFace;

    BaiduSdk(Path sdkRoot) throws ReflectiveOperationException {
        this.sdkRoot = sdkRoot;
        // These classes ship with the SDK deployment and are loaded only in the Worker.
        faceType = Class.forName("com.jni.face.Face");
        mat = Class.forName("org.opencv.core.Mat");
        byteMat = Class.forName("org.opencv.core.MatOfByte");
        imgcodecs = Class.forName("org.opencv.imgcodecs.Imgcodecs");
        core = Class.forName("org.opencv.core.Core");
        engine = faceType.getConstructor().newInstance();
    }

    ObjectNode initialize() throws IOException, ReflectiveOperationException, SdkFailure {
        int code = (Integer) faceType.getMethod("sdkInit", String.class).invoke(engine, "");
        initialized = code == 0;
        boolean authorized = (Boolean) faceType.getMethod("isAuth").invoke(engine);
        if (!initialized || !authorized) {
            ObjectNode error = WorkerFrames.error("batch", "initialize",
                    initialized ? "SDK_NOT_AUTHORIZED" : "SDK_INIT_FAILED", true);
            throw new SdkFailure(error.put("nativeCode", code).put("authorized", authorized));
        }

        ObjectNode noFace = measure(sdkRoot.resolve("probes/01-no-face.jpg"), true);
        nullMeansNoFace = noFace.path("nativeFaceArrayNull").asBoolean(false);
        ObjectNode single = measure(sdkRoot.resolve("probes/02-single-face.jpg"), false);
        if (noFace.path("faceCount").asInt(-1) != 0 || single.path("faceCount").asInt(-1) != 1
                || !single.path("missing").isEmpty()) {
            throw new SdkFailure(WorkerFrames.error("batch", "probe", "SDK_PROBE_FAILED", true));
        }

        ObjectNode result = WorkerFrames.object().put("engineId", "baidu-windows")
                .put("engineVersion", (String) faceType.getMethod("sdkVersion").invoke(null))
                .put("measurementConfigFingerprint", fingerprint())
                .put("metricSemanticsVersion", SEMANTICS).put("authorized", true);
        result.putObject("probes").put("singleFace", true).put("noFace", true)
                .put("noFaceRepresentation", nullMeansNoFace ? "null" : "empty-array");
        CAPABILITIES.forEach(result.putArray("capabilities")::add);
        return result;
    }

    ObjectNode measure(Path path) throws ReflectiveOperationException, SdkFailure {
        return measure(path, false);
    }

    private ObjectNode measure(Path path, boolean knownNoFace)
            throws ReflectiveOperationException, SdkFailure {
        long start = System.nanoTime();
        byte[] jpeg = readPhoto(path);
        String sha256 = hex(digest().digest(jpeg));
        int orientation = JpegExif.orientation(jpeg);
        Object encoded = byteMat.getConstructor(byte[].class).newInstance((Object) jpeg);
        Object decoded = null;
        try {
            if (jpeg.length < 3 || jpeg[0] != (byte) 0xff || jpeg[1] != (byte) 0xd8
                    || jpeg[2] != (byte) 0xff) {
                throw badJpeg(sha256);
            }
            decoded = imgcodecs.getMethod("imdecode", mat, int.class)
                    .invoke(null, encoded, COLOR_WITHOUT_ORIENTATION);
            if ((Boolean) mat.getMethod("empty").invoke(decoded)) throw badJpeg(sha256);

            int width = dimension(decoded, "cols");
            int height = dimension(decoded, "rows");
            normalize(decoded, orientation);
            long address = (Long) mat.getMethod("getNativeObjAddr").invoke(decoded);
            ObjectNode data = WorkerFrames.object().put("sha256", sha256);
            data.putObject("image").put("sourceWidth", width).put("sourceHeight", height)
                    .put("orientation", orientation).put("width", dimension(decoded, "cols"))
                    .put("height", dimension(decoded, "rows"));
            detect(data, address, knownNoFace);
            return data.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        } finally {
            Method release = mat.getMethod("release");
            try {
                if (decoded != null) release.invoke(decoded);
            } finally {
                release.invoke(encoded);
            }
        }
    }

    private void detect(ObjectNode data, long address, boolean knownNoFace)
            throws ReflectiveOperationException, SdkFailure {
        Object boxes = faceType.getMethod("detect", long.class, int.class).invoke(null, address, 0);
        if (boxes == null && !knownNoFace && !nullMeansNoFace) {
            throw new SdkFailure(WorkerFrames.error("file", "detect", "SDK_NULL_DETECTION", true)
                    .put("sha256", data.path("sha256").asText()));
        }
        int count = boxes == null ? 0 : Array.getLength(boxes);
        if (knownNoFace) data.put("nativeFaceArrayNull", boxes == null);
        data.put("faceCount", count);
        ArrayNode frames = data.putArray("boxes");
        ArrayNode missing = data.putArray("missing");
        for (int i = 0; i < count; i++) {
            Object detection = Array.get(boxes, i);
            ObjectNode box = faceBox(detection);
            if (box != null) frames.add(box);
            else if (count == 1) missing.addObject().put("field", "boxes").put("reason", "INVALID_BOX");
        }
        data.putNull("pose").putNull("blur").putNull("illumination").putNull("occlusion");
        if (count == 1) {
            qualityFields(data, missing, "pose", "faceHeadPose", address, POSE_FIELDS, -180, 180);
            metric(data, missing, "blur", quality("faceBlur", address), 0, 1);
            qualityFields(data, missing, "occlusion", "faceOcclusion", address, OCCLUSION_FIELDS, 0, 1);
            // Illumination range and direction require the target host fixtures.
            metric(data, missing, "illumination", quality("faceIllumination", address),
                    -Double.MAX_VALUE, Double.MAX_VALUE);
        }
    }

    private static ObjectNode faceBox(Object box) throws ReflectiveOperationException {
        if (box == null) return null;
        double width = value(box, "width");
        double height = value(box, "height");
        double x = value(box, "centerx") - width / 2.0;
        double y = value(box, "centery") - height / 2.0;
        double score = value(box, "score");
        if (width <= 0 || height <= 0 || !Double.isFinite(width) || !Double.isFinite(height)
                || !Double.isFinite(x) || !Double.isFinite(y)) return null;
        ObjectNode result = WorkerFrames.object().put("x", x).put("y", y).put("width", width)
                .put("height", height);
        if (Double.isFinite(score)) result.put("score", score);
        else result.putNull("score");
        return result;
    }

    private Object quality(String api, long address) throws ReflectiveOperationException {
        return faceType.getMethod(api, long.class).invoke(null, address);
    }

    private static void metric(ObjectNode data, ArrayNode missing, String field, Object array,
                               double min, double max) {
        if (validOne(missing, field, array)) {
            putMetric(data, missing, field, field, ((Number) Array.get(array, 0)).doubleValue(), min, max);
        }
    }

    private void qualityFields(ObjectNode data, ArrayNode missing, String field, String api,
                               long address, String[] fields, double min, double max)
            throws ReflectiveOperationException {
        Object array = quality(api, address);
        if (!validOne(missing, field, array)) return;
        Object value = Array.get(array, 0);
        ObjectNode values = data.putObject(field);
        for (String component : fields) {
            putMetric(values, missing, component, field + "." + component, value(value, component), min, max);
        }
    }

    private static boolean validOne(ArrayNode missing, String field, Object array) {
        if (array != null && Array.getLength(array) == 1 && Array.get(array, 0) != null) return true;
        String reason = array == null || Array.getLength(array) <= 1 ? "EMPTY_RESULT" : "CARDINALITY";
        missing.addObject().put("field", field).put("reason", reason);
        return false;
    }

    private static void putMetric(ObjectNode target, ArrayNode missing, String key, String field,
                                  double value, double min, double max) {
        if (Double.isFinite(value) && value >= min && value <= max) {
            target.put(key, value);
        } else {
            target.putNull(key);
            missing.addObject().put("field", field).put("reason", "INVALID_VALUE");
        }
    }

    private static double value(Object entity, String field) throws ReflectiveOperationException {
        return ((Number) entity.getClass().getField(field).get(entity)).doubleValue();
    }

    private int dimension(Object image, String method) throws ReflectiveOperationException {
        return (Integer) mat.getMethod(method).invoke(image);
    }

    private void normalize(Object image, int orientation) throws ReflectiveOperationException {
        if (orientation >= 5) core.getMethod("transpose", mat, mat).invoke(null, image, image);
        int flip = orientation == 2 || orientation == 6 ? 1
                : orientation == 4 || orientation == 8 ? 0
                : orientation == 3 || orientation == 7 ? -1 : Integer.MAX_VALUE;
        if (flip != Integer.MAX_VALUE) {
            core.getMethod("flip", mat, mat, int.class).invoke(null, image, image, flip);
        }
    }

    private static byte[] readPhoto(Path path) throws SdkFailure {
        try {
            return Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            throw new SdkFailure(WorkerFrames.error("file", "read", "FILE_NOT_FOUND", true));
        } catch (IOException | SecurityException e) {
            throw new SdkFailure(WorkerFrames.error("file", "read", "FILE_READ_FAILED", true));
        }
    }

    private static SdkFailure badJpeg(String sha256) {
        return new SdkFailure(WorkerFrames.error("file", "decode", "JPEG_DECODE_FAILED", false)
                .put("sha256", sha256));
    }

    private String fingerprint() throws IOException {
        MessageDigest digest = digest();
        digest.update((SEMANTICS + "/opencv-3.2/ignore-orientation+exif-1")
                .getBytes(StandardCharsets.UTF_8));
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sdkRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isConfigurationFile(sdkRoot.relativize(path)))
                    .forEach(files::add);
        }
        Path workerRoot = sdkRoot.getParent();
        Path libraries = workerRoot.resolve("lib");
        try (Stream<Path> paths = Files.list(libraries)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> workerRoot.relativize(path).toString()));
        byte[] buffer = new byte[64 * 1024];
        for (Path path : files) {
            digest.update(workerRoot.relativize(path).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            MessageDigest asset = digest();
            try (InputStream source = Files.newInputStream(path)) {
                for (int n; (n = source.read(buffer)) != -1;) asset.update(buffer, 0, n);
            }
            digest.update(asset.digest());
        }
        return hex(digest.digest());
    }

    private static boolean isConfigurationFile(Path relative) {
        String folder = relative.getName(0).toString();
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return folder.equals("config") || folder.equals("models") || folder.equals("probes")
                || name.endsWith(".jar") || name.endsWith(".dll") || name.equals("face.ini");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
        }
        return output.toString();
    }

    @Override
    public void close() throws ReflectiveOperationException {
        if (initialized) {
            initialized = false;
            faceType.getMethod("sdkDestroy").invoke(engine);
        }
    }

    static final class SdkFailure extends Exception {
        final ObjectNode error;
        SdkFailure(ObjectNode error) {
            this.error = error;
        }
    }
}
