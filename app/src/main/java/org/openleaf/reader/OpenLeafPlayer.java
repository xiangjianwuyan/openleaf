package org.openleaf.reader;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.AudioSink;

import org.openleaf.reader.core.AudioDsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Media3 播放器门面（0.14.0 T02，数据 / 播放控制层，不含 UI 布局）。
 *
 * <p>三处 Activity 只依赖本类的 setMediaItem / play / pause / seekTo / 监听回调，
 * 不直接触碰 ExoPlayer API。队列、shuffle、LRC、SP 进度仍由 Activity 负责；
 * 音频焦点 / becoming-noisy 沿用 0.13.0 Activity 原逻辑（封装只暴露 setVolume 供 ducking），
 * 避免 ExoPlayer 内部焦点处理与既有回调双重管理造成行为漂移。</p>
 */
public final class OpenLeafPlayer {
    /** 与 §5.4 对齐；异常 reason 使用 §6.4 固定短串。 */
    public interface Listener {
        void onPrepared();
        void onCompletion();
        void onError(String reason);
        void onTracksChanged();
    }

    private final ExoPlayer player;
    /** 阶段 2-A 音效链；null 表示未启用（老构造路径，零回归）。 */
    private final AudioProcessorChain audioChain;
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private Runnable fadeRunnable;
    private Listener listener;
    private MediaItem.SubtitleConfiguration subtitle;
    private boolean preparedNotified, endedNotified;

    /** 原构造路径：无音效链，行为与 0.31.0 逐字一致。 */
    public OpenLeafPlayer(Context context) { this(context, null); }

    /**
     * 阶段 2-A：chain 非 null 时注入自定义 AudioSink（带 EQ/限幅/频谱处理器），
     * 并显式关闭 audio offload —— 否则 processor 会被静默绕过、开关无效且无报错。
     */
    @UnstableApi public OpenLeafPlayer(Context context, AudioProcessorChain chain) {
        this.audioChain = chain;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        ExoPlayer.Builder builder;
        if (chain == null) {
            builder = new ExoPlayer.Builder(context.getApplicationContext());
        } else {
            DefaultAudioSink sink = new DefaultAudioSink.Builder()
                    .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{chain})
                    .setAudioOffloadSupportProvider((format, attrs) -> AudioOffloadSupport.DEFAULT_UNSUPPORTED)
                    .build();
            DefaultRenderersFactory rf = new DefaultRenderersFactory(context.getApplicationContext()) {
                @Override protected AudioSink buildAudioSink(Context c, boolean floatOutput, boolean hwAc3) {
                    return sink;
                }
            };
            builder = new ExoPlayer.Builder(context.getApplicationContext(), rf);
        }
        player = builder
                .setAudioAttributes(attributes, false) // 焦点仍由 Activity 端原逻辑管理
                .setHandleAudioBecomingNoisy(false)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !preparedNotified) {
                    preparedNotified = true;
                    if (listener != null) listener.onPrepared();
                } else if (state == Player.STATE_ENDED && !endedNotified) {
                    endedNotified = true;
                    if (listener != null) listener.onCompletion();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (listener != null) {
                    listener.onError("未知错误（" + error.getClass().getSimpleName() + "）");
                }
            }

            @Override public void onTracksChanged(Tracks tracks) {
                if (listener != null) listener.onTracksChanged();
            }
        });
    }

    /** 供后续 media3 MediaSession（T03）绑定；T02 不建会话、不新增组件。 */
    public ExoPlayer underlying() { return player; }

    /** 音效链（未启用时为 null）；仅音频播放器 Activity 使用。 */
    @UnstableApi public AudioProcessorChain audioChain() { return audioChain; }

    public void setListener(Listener l) { listener = l; }

    /** 设置字幕（SAF 选 .srt/.vtt）；在 setMediaItem 之前或之后调用均可。 */
    public void setSubtitle(Uri uri, String mime) {
        if (uri == null) return;
        String kind = mime;
        if (kind == null || kind.isEmpty()) {
            String name = uri.getLastPathSegment();
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            kind = lower.endsWith(".vtt") ? MimeTypes.TEXT_VTT
                    : lower.endsWith(".srt") ? MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_VTT;
        }
        subtitle = new MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(kind)
                .setLanguage("zh")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();
    }

    /** 装载媒体并立即 prepare；重复调用会替换当前 item（Activity 切歌走 release + 重建）。 */
    public void setMediaItem(Uri uri, String mime) {
        if (uri == null) throw new IllegalArgumentException("uri 不能为空");
        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
        if (mime != null && !mime.isEmpty()) builder.setMimeType(mime);
        if (subtitle != null) builder.setSubtitleConfigurations(Collections.singletonList(subtitle));
        preparedNotified = false;
        endedNotified = false;
        player.setMediaItem(builder.build());
        player.prepare();
    }

    public void play() { player.play(); }
    public void pause() { player.pause(); }
    public void seekTo(long ms) { player.seekTo(ms); }
    public long duration() { return player.getDuration(); }
    public long position() { return player.getCurrentPosition(); }
    public boolean isPlaying() { return player.isPlaying(); }
    public void setVolume(float volume) { player.setVolume(volume); }
    /** 0.43.0 播放倍速：ExoPlayer 的 PlaybackParameters 倍速，切歌重建后需由调用方重施。 */
    public void setPlaybackSpeed(float speed) { player.setPlaybackSpeed(speed); }

    /** 以 AudioDsp.GainRamp 逐帧提升音量（250ms），对齐 0.13.0 切歌淡入口径。 */
    public void startFadeIn() {
        fadeHandler.removeCallbacks(fadeRunnable);
        final AudioDsp.GainRamp ramp = new AudioDsp.GainRamp(250, 44100);
        ramp.setTarget(1f);
        player.setVolume(0f);
        fadeRunnable = new Runnable() {
            @Override public void run() {
                if (!isPlaying()) return;
                float gain = ramp.next();
                player.setVolume(gain);
                if (gain < 1f) fadeHandler.postDelayed(this, 16);
            }
        };
        fadeHandler.post(fadeRunnable);
    }

    /** 音轨标签（多音轨 UI 在 T03；此处只提供数据）。 */
    @UnstableApi public List<String> trackLabels() {
        List<String> labels = new ArrayList<>();
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                androidx.media3.common.Format format = group.getTrackFormat(i);
                String label = format.label;
                if (label == null || label.isEmpty()) label = format.language;
                labels.add(label == null || label.isEmpty() ? "音轨 " + (labels.size() + 1) : label);
            }
        }
        return labels;
    }

    /** 按 {@link #trackLabels()} 的下标选择音轨。 */
    @UnstableApi public void selectTrack(int index) {
        int seen = -1;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                if (++seen == index) {
                    TrackSelectionParameters params = player.getTrackSelectionParameters()
                            .buildUpon()
                            .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i))
                            .build();
                    player.setTrackSelectionParameters(params);
                    return;
                }
            }
        }
    }

    /** 释放播放器与淡入回调；调用后本实例不可再用。 */
    public void release() {
        fadeHandler.removeCallbacks(fadeRunnable);
        fadeRunnable = null;
        listener = null;
        player.release();
    }
}
