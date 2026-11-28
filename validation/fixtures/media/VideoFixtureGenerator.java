import android.media.*;
import android.graphics.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Device-side synthetic decodable video + subtitle fixture. Runs via app_process on Android. */
public final class VideoFixtureGenerator {
    static final int W = 480, H = 320, FPS = 30, SECONDS = 3;
    static final String MIME = "video/avc";
    static final String OUT = "/sdcard/Download/ol-data01-probe/video-fixture";

    static String sha(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }
    static void log(String s) { System.out.println("VIDEOFIX " + s); }

    public static void main(String[] args) {
        try {
            // 1. enumerate encoders (diagnostic)
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            String encoderName = null;
            StringBuilder all = new StringBuilder();
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String mime : info.getSupportedTypes()) {
                    all.append(info.getName()).append('|').append(mime).append('\n');
                    if (mime.equals(MIME)) {
                        MediaCodecInfo.CodecCapabilities c = info.getCapabilitiesForType(mime);
                        MediaCodecInfo.VideoCapabilities vc = c.getVideoCapabilities();
                        if (vc != null && vc.isSizeSupported(W, H)) encoderName = info.getName();
                    }
                }
            }
            log("ENCODERS_BEGIN\n" + all + "ENCODERS_END");
            if (encoderName == null) { log("FATAL no H.264 encoder for " + W + "x" + H); return; }
            log("SELECTED " + encoderName);

            File dir = new File(OUT); dir.mkdirs();
            File mp4 = new File(dir, "synthetic-video.mp4");

            // 2. ByteBuffer input mode: explicit PTS per frame
            MediaCodec codec = MediaCodec.createByCodecName(encoderName);
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, W, H);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 600000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            MediaMuxer muxer = new MediaMuxer(mp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(codec.getOutputFormat());
            muxer.start();

            int total = FPS * SECONDS;
            for (int frame = 0; frame < total; frame++) {
                long us = (long) frame * 1_000_000L / FPS;
                byte[] yuv = renderYuv(frame, total);
                int in = codec.dequeueInputBuffer(100000);
                if (in < 0) { log("WARN input timeout at frame " + frame); continue; }
                ByteBuffer bb = codec.getInputBuffer(in);
                bb.clear(); bb.put(yuv);
                codec.queueInputBuffer(in, 0, yuv.length, us, 0);
                drain(codec, muxer, track);
            }
            int eos = codec.dequeueInputBuffer(100000);
            if (eos >= 0) codec.queueInputBuffer(eos, 0, 0, (long) total * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainUntilEos(codec, muxer, track);
            muxer.stop(); muxer.release(); codec.stop(); codec.release();
            log("MP4 " + mp4.getAbsolutePath() + " bytes=" + mp4.length() + " sha256=" + sha(readAll(mp4)));

            // 3. subtitle SRT (Chinese + ASCII, explicit intervals)
            String srt = "1\n00:00:00,500 --> 00:00:01,200\n字幕A 安全区测试\n\n"
                       + "2\n00:00:01,300 --> 00:00:02,200\nASCII SUB B 12345\n\n"
                       + "3\n00:00:02,300 --> 00:00:02,900\n字幕C 尾部检查\n\n";
            File srtFile = new File(dir, "synthetic-video.srt");
            writeAll(srtFile, srt.getBytes(StandardCharsets.UTF_8));
            log("SRT " + srtFile.getAbsolutePath() + " bytes=" + srtFile.length() + " sha256=" + sha(readAll(srtFile)));

            // 4. key frames via MediaMetadataRetriever (OPTION_CLOSEST for precise timestamps)
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(fileDataSource(mp4));
            log("MP4_META duration=" + mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                + " width=" + mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                + " height=" + mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            for (long ms : new long[]{1000L, 1500L, 2500L}) {
                Bitmap bmp = mmr.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (bmp == null) { log("FRAME " + ms + "ms NULL"); continue; }
                File png = new File(dir, "frame-" + ms + "ms.png");
                try (OutputStream os = new FileOutputStream(png)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, os); }
                log("FRAME " + ms + "ms " + png.getName() + " bytes=" + png.length() + " sha256=" + sha(readAll(png)));
                bmp.recycle();
            }
            mmr.release();

            // 5. MediaPlayer prepare validation (no surface: container/track decode readiness)
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(fileDataSource(mp4));
            player.prepare();
            log("MEDIAPLAYER prepare=OK duration=" + player.getDuration());
            player.release();
            log("DONE PASS");
        } catch (Throwable t) {
            log("FATAL " + t.getClass().getName() + ": " + t.getMessage());
            StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); log(sw.toString());
        }
    }

    static void drain(MediaCodec codec, MediaMuxer muxer, int track) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (true) {
            int out = codec.dequeueOutputBuffer(bi, 100000);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break;
            if (out < 0) continue;
            if (bi.size > 0) muxer.writeSampleData(track, codec.getOutputBuffer(out), bi);
            codec.releaseOutputBuffer(out, false);
        }
    }
    static void drainUntilEos(MediaCodec codec, MediaMuxer muxer, int track) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        for (int guard = 0; guard < 1000; guard++) {
            int out = codec.dequeueOutputBuffer(bi, 100000);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (out < 0) continue;
            if (bi.size > 0) muxer.writeSampleData(track, codec.getOutputBuffer(out), bi);
            codec.releaseOutputBuffer(out, false);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /** Render one frame to NV12 (Y plane + interleaved UV), geometry only, no text/font. */
    static byte[] renderYuv(int frame, int total) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int second = frame / FPS;
        c.drawRGB(90 + second * 40, 40 + second * 30, 200 - second * 40);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        drawDigit(c, p, second + 1, W / 2f - 55, 45, 110, 210);
        p.setColor(Color.YELLOW);
        c.drawRect(12, 18, 12 + (W - 24) * (frame + 1) / (float) total, 30, p);
        for (int k = 0; k <= second; k++) c.drawRect(20 + k * 34, H - 40, 44 + k * 34, H - 16, p);

        int[] px = new int[W * H];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        bmp.recycle();
        byte[] yuv = new byte[W * H * 3 / 2];
        int yp = 0, uvp = W * H;
        for (int row = 0; row < H; row += 2) {
            for (int col = 0; col < W; col += 2) {
                int p0 = px[row * W + col], p1 = px[row * W + col + 1],
                    p2 = px[(row + 1) * W + col], p3 = px[(row + 1) * W + col + 1];
                int r = (red(p0) + red(p1) + red(p2) + red(p3)) / 4;
                int g = (green(p0) + green(p1) + green(p2) + green(p3)) / 4;
                int b = (blue(p0) + blue(p1) + blue(p2) + blue(p3)) / 4;
                yuv[yp++] = clamp(y(r, g, b));
                yuv[yp++] = clamp(y(red(p1), green(p1), blue(p1)));
                yuv[yp++] = clamp(y(red(p2), green(p2), blue(p2)));
                yuv[yp++] = clamp(y(red(p3), green(p3), blue(p3)));
                yuv[uvp++] = clamp(u(r, g, b));
                yuv[uvp++] = clamp(v(r, g, b));
            }
        }
        return yuv;
    }
    static int red(int p) { return (p >> 16) & 0xFF; }
    static int green(int p) { return (p >> 8) & 0xFF; }
    static int blue(int p) { return p & 0xFF; }
    static int y(int r, int g, int b) { return (int) (0.299*r + 0.587*g + 0.114*b); }
    static int u(int r, int g, int b) { return (int) (-0.1687*r - 0.3313*g + 0.5*b + 128); }
    static int v(int r, int g, int b) { return (int) (0.5*r - 0.4187*g - 0.0813*b + 128); }
    static byte clamp(int x) { return (byte) Math.max(0, Math.min(255, x)); }

    static void drawDigit(Canvas c, Paint p, int digit, float x, float y, float w, float h) {
        float t = 14; float mid = y + h / 2f;
        boolean[] seg = switch (digit) {
            case 1 -> new boolean[]{false,true,true,false,false,false,false};
            case 2 -> new boolean[]{true,true,false,true,true,false,true};
            case 3 -> new boolean[]{true,true,true,true,false,false,true};
            default -> new boolean[]{true,true,true,true,true,true,true};
        };
        if (seg[0]) c.drawRect(x+t, y, x+w-t, y+t, p);
        if (seg[1]) c.drawRect(x+w-t, y, x+w, mid, p);
        if (seg[2]) c.drawRect(x+w-t, mid, x+w, y+h, p);
        if (seg[3]) c.drawRect(x+t, y+h-t, x+w-t, y+h, p);
        if (seg[4]) c.drawRect(x, mid, x+t, y+h, p);
        if (seg[5]) c.drawRect(x, y, x+t, mid, p);
        if (seg[6]) c.drawRect(x+t, mid-t/2, x+w-t, mid+t/2, p);
    }

    static MediaDataSource fileDataSource(final File f) throws Exception {
        final RandomAccessFile raf = new RandomAccessFile(f, "r");
        return new MediaDataSource() {
            @Override public int readAt(long position, byte[] buffer, int offset, int size) throws java.io.IOException {
                raf.seek(position); return raf.read(buffer, offset, size);
            }
            @Override public long getSize() throws java.io.IOException { return raf.length(); }
            @Override public void close() throws java.io.IOException { raf.close(); }
        };
    }
    static byte[] readAll(File f) throws Exception { try (InputStream in = new FileInputStream(f)) { return in.readAllBytes(); } }
    static void writeAll(File f, byte[] b) throws Exception { try (OutputStream o = new FileOutputStream(f)) { o.write(b); } }
}
