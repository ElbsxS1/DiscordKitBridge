package br.asthen.kitbridge.logs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Daily log files (logs/yyyy-MM-dd.log). Writes happen on a dedicated thread so the
 * server thread never waits on disk. Values are sanitised against log injection.
 */
public final class DeliveryLogger implements AutoCloseable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path dir;
    private final boolean enabled;
    private final Consumer<String> console;
    private final ExecutorService io;

    public DeliveryLogger(Path dir, boolean enabled, Consumer<String> console) {
        this.dir = dir;
        this.enabled = enabled;
        this.console = console;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DiscordKitBridge-Log");
            t.setDaemon(true);
            return t;
        });
    }

    /** @param kv alternating key, value pairs */
    public void log(String event, Object... kv) {
        StringBuilder sb = new StringBuilder();
        sb.append(LocalDateTime.now().format(TS)).append(" [").append(event).append(']');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=').append(clean(String.valueOf(kv[i + 1])));
        }
        String line = sb.toString();
        if (console != null) console.accept(line.substring(20)); // drop timestamp, the server adds its own
        if (!enabled) return;
        Path file = dir.resolve(LocalDate.now() + ".log");
        try {
            io.execute(() -> {
                try {
                    Files.createDirectories(dir);
                    Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // logging must never break a delivery
                }
            });
        } catch (RuntimeException rejected) {
            // executor already shut down
        }
    }

    /** Neutralises control characters and caps the length. Quotes values that contain spaces or '='. */
    public static String clean(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder(Math.min(v.length(), 200));
        for (int i = 0; i < v.length() && sb.length() < 200; i++) {
            char c = v.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        String r = sb.toString();
        if (r.indexOf(' ') >= 0 || r.indexOf('=') >= 0 || r.isEmpty()) return '"' + r.replace("\"", "'") + '"';
        return r;
    }

    /** Deletes log files older than {@code days} days. */
    public void purgeOlderThan(int days) {
        if (days <= 0) return;
        LocalDate limit = LocalDate.now().minusDays(days);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.log")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                try {
                    if (LocalDate.parse(n.substring(0, n.length() - 4)).isBefore(limit)) Files.deleteIfExists(p);
                } catch (RuntimeException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException ignored) {
            // directory does not exist yet
        }
    }

    @Override
    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
