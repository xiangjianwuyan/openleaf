package org.openleaf.reader;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Opt-in synthetic checks for BookStore's bounded copy/hash primitive.
 * JVM main requires compiled app classes and android.jar on the classpath; it never initializes an
 * Android Context or opens a real book. Cancellation checks additionally require the Android runtime
 * (call run() from a debug harness). No manifest or production entry point is changed.
 */
public final class BookStoreImportChecks {
    private static final long MIB = 1024L * 1024;
    private static final Method COPY = method("copyAndHash", InputStream.class, File.class,
            String.class, CancellationSignal.class, String.class, long.class, BookStore.ImportProgress.class);
    private static final Method HASH = method("hashFile", File.class, CancellationSignal.class, long.class);

    private BookStoreImportChecks() { }

    public static void main(String[] args) throws Exception {
        runCopies();
        System.out.println("PASS synthetic BookStore copy/hash checks; Android cancellation checks not run");
    }

    static void run() throws Exception {
        runCopies();
        runProgressCallbacks();
        File root = Files.createTempDirectory("openleaf-import-cancel-").toFile();
        File destination = new File(root, "synthetic.part");
        try {
            CancellationSignal already = new CancellationSignal();
            already.cancel();
            InputStream untouched = new InputStream() {
                @Override public int read() { throw new AssertionError("read after precancellation"); }
            };
            expectCanceled(() -> copy(untouched, destination, "txt", already));
            require(!destination.exists(), "precancellation created a file");

            CancellationSignal during = new CancellationSignal();
            InputStream cancelsOnRead = new InputStream() {
                @Override public int read() { during.cancel(); return 'x'; }
                @Override public int read(byte[] bytes, int offset, int length) {
                    bytes[offset] = 'x'; during.cancel(); return 1;
                }
            };
            expectCanceled(() -> copy(cancelsOnRead, destination, "txt", during));
            require(destination.length() == 0, "canceled read was written");
        } finally {
            Files.deleteIfExists(destination.toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    private static void runCopies() throws Exception {
        File root = Files.createTempDirectory("openleaf-import-copy-").toFile();
        File destination = new File(root, "synthetic.part");
        try {
            String sha = copy(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)),
                    destination, "txt", null);
            require("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(sha),
                    "SHA-256 changed");
            require(sha.equals(invoke(HASH, destination, null, 20 * MIB)), "file hash differs from copy hash");
            expectIo(() -> copy(new ByteArrayInputStream(new byte[0]), destination, "txt", null));
            PatternStream intermittent = new PatternStream(4097, true);
            require(copy(intermittent, destination, "txt", null).equals(expectedHash(4097)),
                    "zero-return fallback lost bytes");
            require(destination.length() == 4097, "zero-return copy size changed");
            boundaryIfFeasible(destination, "txt");
            boundaryIfFeasible(destination, "epub");
            boundaryIfFeasible(destination, "pdf");
        } finally {
            Files.deleteIfExists(destination.toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    private static void runProgressCallbacks() throws Exception {
        File root = Files.createTempDirectory("openleaf-import-progress-").toFile();
        File destination = new File(root, "synthetic.part");
        try {
            long knownSize = 512L * 1024;
            ArrayList<ProgressEvent> known = new ArrayList<>();
            BookStore.ImportProgress knownObserver = (name, phase, bytes, total) ->
                    known.add(new ProgressEvent(name, phase, bytes, total, android.os.SystemClock.uptimeMillis()));
            String hash = copy(new PatternStream(knownSize, false, 130), destination, "pdf", null,
                    "near-limit-synthetic.pdf", knownSize, knownObserver);
            require(hash.equals(expectedHash(knownSize)), "progress callback changed PDF copy bytes");
            require(known.size() >= 5, "known-size copy did not report periodic progress");
            ProgressEvent first = known.get(0), last = known.get(known.size() - 1);
            require(first.name.equals("near-limit-synthetic.pdf") && first.phase == BookStore.ImportPhase.COPY
                    && first.bytes == 0 && first.total == knownSize, "known-size opening callback incorrect");
            require(last.name.equals(first.name) && last.phase == BookStore.ImportPhase.COPY
                    && last.bytes == knownSize && last.total == knownSize, "known-size EOF callback incorrect");
            for (int i = 1; i < known.size() - 1; i++) {
                ProgressEvent event = known.get(i), previous = known.get(i - 1);
                require(event.name.equals(first.name) && event.phase == BookStore.ImportPhase.COPY,
                        "intermediate callback identity or phase changed");
                require(event.bytes >= previous.bytes && event.bytes <= knownSize,
                        "known-size progress was not monotonic or exceeded its bound");
                require(event.time - previous.time >= 120,
                        "intermediate progress callbacks were not throttled to 120 ms");
                require(event.total == (event.bytes < knownSize ? knownSize : -1),
                        "known-size interim total was incorrect");
            }
            System.out.println("PASS known-size copy progress and 120 ms throttling");

            ArrayList<ProgressEvent> unknown = new ArrayList<>();
            BookStore.ImportProgress unknownObserver = (name, phase, bytes, total) ->
                    unknown.add(new ProgressEvent(name, phase, bytes, total, android.os.SystemClock.uptimeMillis()));
            copy(new PatternStream(31, false), destination, "pdf", null,
                    "unknown-pipe.pdf", -1, unknownObserver);
            require(unknown.size() == 2, "small unknown-size stream should report opening and EOF only");
            require(unknown.get(0).bytes == 0 && unknown.get(0).total == -1
                    && unknown.get(1).bytes == 31 && unknown.get(1).total == 31,
                    "unknown-size callback did not remain indeterminate until EOF");
            require("unknown-pipe.pdf".equals(unknown.get(0).name)
                    && unknown.get(0).phase == BookStore.ImportPhase.COPY
                    && unknown.get(1).phase == BookStore.ImportPhase.COPY,
                    "unknown-size callback identity or phase incorrect");
            System.out.println("PASS unknown-size copy progress remains indeterminate until EOF");

            CancellationSignal cancelFromProgress = new CancellationSignal();
            ArrayList<ProgressEvent> cancelled = new ArrayList<>();
            BookStore.ImportProgress cancelObserver = (name, phase, bytes, total) -> {
                cancelled.add(new ProgressEvent(name, phase, bytes, total, android.os.SystemClock.uptimeMillis()));
                if (bytes == 0) cancelFromProgress.cancel();
            };
            expectCanceled(() -> copy(new PatternStream(knownSize, false, 130), destination, "pdf",
                    cancelFromProgress, "cancel-at-start.pdf", knownSize, cancelObserver));
            require(cancelled.size() == 1 && destination.length() == 0,
                    "cancellation from opening progress continued reading or writing");
            System.out.println("PASS cancellation from progress callback stops before reading");
        } finally {
            Files.deleteIfExists(destination.toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    /**
     * 按产品真实上限做拷贝边界检查；上限大到不适合在设备上实际拷贝时明确跳过并打印原因。
     *
     * <p>此前这里写死 20 MiB / 80 MiB，而 {@code BookParser.MAX_TXT_BYTES} 与
     * {@code MAX_IMPORT_BYTES} 自 0.13.0 起都是 512 MiB —— 断言与产品恒不相等，本套件长期在
     * 第 10 项就中断，后面的检查等于从未在真机上跑过。上限本身由 core-tests 的
     * BookParserTest / ImportValidationTest 用稀疏文件精确覆盖（512 MiB + 1 字节），
     * 设备上再拷 1 GiB 只增加耗时、不增加证据，所以这里如实 SKIP 而不是假 PASS。
     */
    private static void boundaryIfFeasible(File destination, String format) throws Exception {
        long limit = BookStore.importLimit(format);
        if (limit > 0 && limit <= 128L * MIB) { boundary(destination, format, limit); return; }
        System.out.println("SKIP " + format + " copy boundary: product limit is " + (limit / MIB)
                + " MiB, too large for a device copy test; cap covered by core-tests");
    }

    private static void boundary(File destination, String format, long limit) throws Exception {
        long start = System.nanoTime();
        PatternStream exact = new PatternStream(limit, false);
        String actual = copy(exact, destination, format, null);
        require(destination.length() == limit && actual.equals(expectedHash(limit)), "exact limit rejected or corrupted");
        PatternStream tooLarge = new PatternStream(limit + 1024 * 1024, false);
        expectIo(() -> copy(tooLarge, destination, format, null));
        require(tooLarge.consumed == limit + 1, "copy read beyond its limit probe");
        require(destination.length() <= limit, "copy wrote bytes above its bound");
        System.out.println("PASS " + format + " " + (limit / MIB) + " MiB boundary in "
                + ((System.nanoTime() - start) / 1000000) + " ms (synthetic JVM I/O, not Android import timing)");
    }

    private static String copy(InputStream input, File file, String format, CancellationSignal cancellation)
            throws Exception {
        return copy(input, file, format, cancellation, "synthetic." + format, -1, null);
    }

    private static String copy(InputStream input, File file, String format, CancellationSignal cancellation,
                               String name, long expected, BookStore.ImportProgress progress) throws Exception {
        return (String) invoke(COPY, input, file, format, cancellation, name, expected, progress);
    }

    private static final class ProgressEvent {
        final String name;
        final BookStore.ImportPhase phase;
        final long bytes, total, time;
        ProgressEvent(String name, BookStore.ImportPhase phase, long bytes, long total, long time) {
            this.name = name; this.phase = phase; this.bytes = bytes; this.total = total; this.time = time;
        }
    }

    private static Method method(String name, Class<?>... types) {
        try {
            Method result = BookStore.class.getDeclaredMethod(name, types);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static String expectedHash(long length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[1024];
        Arrays.fill(block, (byte) 'a');
        for (long remaining = length; remaining > 0; remaining -= Math.min(remaining, block.length))
            digest.update(block, 0, (int) Math.min(remaining, block.length));
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }

    private interface Check { void run() throws Exception; }
    private static void expectIo(Check check) throws Exception {
        try { check.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("unsafe copy was accepted");
    }
    private static void expectCanceled(Check check) throws Exception {
        try { check.run(); }
        catch (OperationCanceledException expected) { return; }
        throw new AssertionError("cancellation was ignored");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** A fixed-memory synthetic stream; optionally returns zero on alternating bulk reads. */
    private static final class PatternStream extends InputStream {
        private final long length;
        private final boolean intermittent;
        private final int delayMillis;
        private boolean returnZero;
        long consumed;
        PatternStream(long length, boolean intermittent) { this(length, intermittent, 0); }
        PatternStream(long length, boolean intermittent, int delayMillis) {
            this.length = length; this.intermittent = intermittent; this.delayMillis = delayMillis;
            this.returnZero = intermittent;
        }
        @Override public int read() {
            if (consumed == length) return -1;
            consumed++; return 'a';
        }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (consumed == length) return -1;
            if (intermittent && returnZero) { returnZero = false; return 0; }
            returnZero = intermittent;
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("synthetic stream interrupted", interrupted);
                }
            }
            int result = (int) Math.min(count, length - consumed);
            Arrays.fill(bytes, offset, offset + result, (byte) 'a');
            consumed += result;
            return result;
        }
    }
}
