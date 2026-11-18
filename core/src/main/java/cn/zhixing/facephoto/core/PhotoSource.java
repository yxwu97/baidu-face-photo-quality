package cn.zhixing.facephoto.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class PhotoSource {
    private static final DateTimeFormatter CAPTURE_TIME =
            DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Comparator<String> NULLABLE_PATH_ORDER = Comparator.nullsLast(Comparator.naturalOrder());
    private static final Comparator<SourcePhoto> ORDER = Comparator
            .comparing(SourcePhoto::date, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SourcePhoto::time, NULLABLE_PATH_ORDER)
            .thenComparing(SourcePhoto::relativePath);

    private PhotoSource() {
    }

    public record SourcePhoto(String relativePath, String fileName, String date, String time,
                              String captureStatus, Integer rec, Integer live) {
    }

    public record Scan(Path root, List<SourcePhoto> photos, int unreadableDirectories) {
    }

    public static Path root(Path selected) throws IOException {
        Path root = selected.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(root)) {
            throw new IOException("所选目录不可读");
        }
        return root;
    }

    public static Scan scan(Path selected) throws IOException {
        Path root = root(selected);
        ScanVisitor visitor = new ScanVisitor(root);
        Files.walkFileTree(root, visitor);
        visitor.photos.sort(ORDER);
        return new Scan(root, List.copyOf(visitor.photos), visitor.unreadable);
    }

    public static Path resolve(Path root, String relativePath) throws IOException {
        Path relative = Path.of(relativePath);
        Path candidate = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !candidate.startsWith(root) || candidate.equals(root)
                || crossesLink(root, candidate) || !candidate.toString().equals(candidate.toRealPath().toString())
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("文件路径无效或越出所选目录");
        }
        return candidate;
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean crossesLink(Path root, Path file) throws IOException {
        Path path = root;
        for (Path component : root.relativize(file)) {
            path = path.resolve(component);
            if (link(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) {
                return true;
            }
        }
        return false;
    }

    private static boolean link(BasicFileAttributes attributes) {
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static SourcePhoto describe(Path root, Path file) {
        String name = file.getFileName().toString();
        String[] fields = name.substring(0, name.lastIndexOf('.')).split("_", -1);
        String time = fields[0].matches("[0-9]{6}") ? parseTime(fields[0]) : null;
        String status = fields.length > 1 && !fields[1].isBlank() && !fields[1].startsWith("rec")
                && !fields[1].startsWith("live") ? fields[1] : null;
        Integer rec = null;
        Integer live = null;
        for (int index = status == null ? 1 : 2; index < fields.length; index++) {
            if (fields[index].startsWith("rec")) {
                rec = parseInteger(fields[index].substring(3));
            } else if (fields[index].startsWith("live")) {
                live = parseInteger(fields[index].substring(4));
            }
        }
        return new SourcePhoto(root.relativize(file).toString().replace('\\', '/'), name,
                captureDate(root, file.getParent()), time, status, rec, live);
    }

    private static String captureDate(Path root, Path directory) {
        for (Path current = directory; current != null && current.startsWith(root); current = current.getParent()) {
            Path name = current.getFileName();
            if (name == null || !name.toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(name.toString());
                if (date.getYear() > 0) {
                    return date.toString();
                }
            } catch (DateTimeParseException ignored) {
                // A more distant ancestor may contain the nearest valid date.
            }
        }
        return null;
    }

    private static String parseTime(String value) {
        try {
            return LocalTime.parse(value, CAPTURE_TIME).format(DISPLAY_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class ScanVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final List<SourcePhoto> photos = new ArrayList<>();
        private int unreadable;

        private ScanVisitor(Path root) {
            this.root = root;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            if (link(attributes)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            try {
                if (directory.toString().equals(directory.toRealPath().toString())) {
                    return FileVisitResult.CONTINUE;
                }
            } catch (IOException error) {
                return visitFileFailed(directory, error);
            }
            return FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!link(attributes) && attributes.isRegularFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
                photos.add(describe(root, file));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
            if (path.equals(root)) {
                throw error;
            }
            try {
                if (link(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) {
                    return FileVisitResult.CONTINUE;
                }
            } catch (IOException ignored) {
                // The unavailable entry cannot be inspected further.
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                unreadable++;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
            return error == null ? FileVisitResult.CONTINUE : visitFileFailed(directory, error);
        }
    }
}
