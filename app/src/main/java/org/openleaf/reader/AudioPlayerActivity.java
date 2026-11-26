package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.openleaf.reader.core.AudioCapability;
import org.openleaf.reader.core.Lyrics;
import org.openleaf.reader.core.PlaybackQueue;
import org.openleaf.reader.core.SpeechText;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 本地音频播放器：借鉴 LocalPlayer（MIT）的队列状态机、音频焦点四态、耳机拔出暂停设计，
 * 由 OpenLeaf 自行用原生 Java 实现；0.14.0 T02 起播放内核改为 Media3 {@link OpenLeafPlayer}。队列以稳定标识（文件路径）持久化，
 * 支持 shuffle/repeat 三态、LRC 歌词、MediaSession 媒体键，进度按 ReadingHabit 的
 * 「跳变不计时 + 余数结转」口径折算为页数计入阅读统计。
 */
public final class AudioPlayerActivity extends Activity {
    /** debug-only 播放状态日志 tag；所有日志由 debugLog 守卫。 */
    private static final String TAG = "OL-PlaybackState";
    /** debuggable 包才输出日志；onCreate 开头由 ApplicationInfo.FLAG_DEBUGGABLE 计算一次。 */
    private boolean debugLog;
    /** 仅用于 debug 日志的播放器实例序号：创建时递增，释放时读取同一值。 */
    private int debugInstanceSeq;
    /** 队列顺序枚举。 */
    private enum Repeat { NONE, ONE, ALL }

    private final List<String> queue = new ArrayList<>();
    private int currentIndex = -1;
    private OpenLeafPlayer player;
    private Repeat repeat = Repeat.NONE;
    private boolean shuffled;
    private List<String> originalOrder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) {
                // 歌词更新与计时解耦：没加载 LRC 也该计时，以前「有歌词才统计」漏掉了大多数曲目
                if (lyrics != null) updateLyrics(player.position());
                long now = player.position();
                if (debugLog) android.util.Log.d(TAG, "position=" + now + " playing=" + player.isPlaying() + " idx=" + currentIndex);
                tickAudioStats();
                lastPosition = now;
                updateProgress(now);
                // 阶段 2-A：频谱刷新（UI 线程，只读 chain 的快照）
                if (spectrumVisible && spectrumView != null && player != null) {
                    AudioProcessorChain chain = player.audioChain();
                    if (chain != null) { chain.refreshSpectrum(); spectrumView.setLevels(chain.drainSpectrum()); }
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    /** 0.45.0 睡眠定时：复用 TTS 的纯状态机，音频侧持有独立实例，不持久化。 */
    private final SpeechText.SleepTimer sleepTimer = new SpeechText.SleepTimer();
    private TextView sleepView;
    /** 到点后置位：阻止焦点续播与自动切歌；用户主动操作时清零。 */
    private boolean sleepFired;
    /** 独立 ticker：不塞进上方 isPlaying 守卫，暂停期间也能继续倒计时。 */
    private final Runnable sleepTicker = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (sleepTimer.kind() != SpeechText.SleepTimer.Kind.MINUTES) return;
            if (sleepTimer.expired(now)) {
                pausePlayback();
                sleepTimer.off();
                sleepFired = true;
                if (sleepView != null) sleepView.setText("睡眠：关");
                main.removeCallbacks(this); // 关掉后不再自转
                return;
            }
            long left = sleepTimer.remainingSeconds(now);
            if (sleepView != null) {
                sleepView.setText("睡眠：" + (left / 60) + ":" + String.format(Locale.ROOT, "%02d", left % 60));
            }
            main.postDelayed(this, 1000);
        }
    };

    private long lastPosition;
    /** 听书计时的墙上起点；用 elapsedRealtime 避免用户改系统时间产生重复事件键。 */
    private long audioStatsMark;
    private long audioStatsSegment;

    /**
     * 每满 60 秒落一段 AUDIO 时长。按曲目 URI 的稳定指纹归属到非书媒体表，
     * 绝不写 reading_time（它的 book_id 有外键，曲目 URI 曾在这里把主线程崩掉）。
     */
    private void tickAudioStats() {
        long wall = SystemClock.elapsedRealtime();
        if (audioStatsMark <= 0) { audioStatsMark = wall; audioStatsSegment = wall; return; }
        long elapsed = wall - audioStatsMark;
        if (elapsed < 60_000L) return;
        audioStatsMark = wall;
        String path = currentPath();
        if (path == null || path.isEmpty()) return;
        String key = org.openleaf.reader.core.MediaAttribution.fileKey(path);
        String name = Uri.parse(path).getLastPathSegment();
        try {
            new ReadingStatsStore(this).addMediaSeconds(
                    org.openleaf.reader.core.ReadingLedger.Kind.AUDIO,
                    key, name, elapsed / 1000, audioStatsSegment);
        } catch (RuntimeException ignored) {
            // 统计失败不该打断播放
        }
        audioStatsSegment = wall;
    }
    /** 切歌淡入迁到 {@link OpenLeafPlayer#startFadeIn()}（内部仍用 AudioDsp.GainRamp）。 */

    private TextView titleView, lyricView, positionView, repeatView, shuffleView, mediaKeyHint;
    /** v2.2：常驻 speedView 不再创建，倍速值改由「更多」菜单按 speedLabel() 动态生成；cycleSpeed 的空守卫保持不变。 */
    private TextView speedView;
    /** v2.2：一次点按入口的队列按钮，label 随队列长度更新。 */
    private Button queueButton;
    private SeekBar seekBar;
    private boolean seeking;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean pausedByFocus, duckedByFocus;
    private boolean becomingNoisyRegistered;
    private android.media.session.MediaSession mediaSession;

    /** 歌词行由 core {@link Lyrics.Line} 承接（LRC / 增强 LRC / SRT / TXT）。 */
    private List<Lyrics.Line> lyrics = Collections.emptyList();

    private static final String PREF = "audio_player";
    private static final String KEY_QUEUE = "playback_queue";
    private static final String KEY_INDEX = "current_index";
    private static final String KEY_REPEAT = "repeat_mode";
    private static final String KEY_SHUFFLE = "shuffle";
    // 阶段 2-A 音效链配置
    private static final String KEY_EQ_ON = "eq_enabled";
    private static final String KEY_CLIP_ON = "clip_enabled";
    private static final String KEY_EQ_GAINS = "eq_gains";
    private static final String KEY_SPECTRUM_ON = "spectrum_enabled";
    // 0.43.0 播放倍速与「下一首播放」
    private static final String KEY_SPEED = "playback_speed";
    private static final int REQ_PLAY_NEXT = 1003;
    private float speed = 1f;
    private boolean eqOn, clipOn, spectrumOn;
    private final float[] eqGains = new float[org.openleaf.reader.core.AudioEq.BANDS.length];
    private SpectrumView spectrumView;
    private boolean spectrumVisible;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        debugLog = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        restoreState();
        buildUi();
        registerBecomingNoisy();
        setupMediaSession();
        main.post(ticker);
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(ticker);
        main.removeCallbacks(sleepTicker);
        releasePlayer();
        if (becomingNoisyRegistered) { unregisterReceiver(noisy); becomingNoisyRegistered = false; }
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        abandonFocus();
        super.onDestroy();
    }

    @Override protected void onPause() {
        super.onPause();
        saveState();
    }

    // ---------- UI ----------

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        root.addView(Ui.title(this, "本地音频", 22));
        titleView = Ui.text(this, "未选择音频", 16, Ui.INK);
        root.addView(titleView);
        mediaKeyHint = Ui.text(this, "", 12, Ui.MUTED);
        mediaKeyHint.setVisibility(View.GONE);
        root.addView(mediaKeyHint);
        lyricView = Ui.text(this, "♪", 15, Ui.MUTED);
        lyricView.setMinLines(2);
        root.addView(lyricView);
        Ui.gap(root, 12);

        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (player != null) { player.seekTo(s.getProgress()); lastPosition = s.getProgress(); }
            }
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(-1, -2));
        positionView = Ui.text(this, "00:00 / 00:00", 12, Ui.MUTED);
        root.addView(positionView);
        Ui.gap(root, 16);

        LinearLayout controls = Ui.row(this);
        controls.addView(Ui.button(this, "⏮ 上一首", this::previous, "上一首"));
        Button play = Ui.primary(this, "▶ 播放", this::togglePlay);
        play.setContentDescription("播放或暂停"); // Ui.primary 为 3 参无 desc，手动补无障碍
        controls.addView(play);
        controls.addView(Ui.button(this, "⏭ 下一首", this::next, "下一首"));
        root.addView(controls);

        // v2.2 一次点按入口：歌词 / 队列 / 音效常驻；低频与配置型操作收进「更多」。
        LinearLayout quickRow = Ui.row(this);
        quickRow.addView(Ui.button(this, "歌词", this::openLyricsPanel, "查看歌词"));
        queueButton = Ui.button(this, "队列 " + queue.size(), this::openQueuePanel, "查看播放队列");
        quickRow.addView(queueButton);
        quickRow.addView(Ui.button(this, "♪ 音效", this::openSoundPanel, "音效设置"));
        quickRow.addView(Ui.button(this, "更多", this::openMoreMenu, "更多播放操作"));
        root.addView(quickRow);

        LinearLayout modes = Ui.row(this);
        shuffleView = Ui.button(this, "随机：关", this::toggleShuffle, "随机播放");
        repeatView = Ui.button(this, "循环：关", this::toggleRepeat, "循环模式");
        modes.addView(shuffleView); modes.addView(repeatView); // v2.2：倍速移入「更多」
        root.addView(modes);

        // v2.2：睡眠仅保留常驻状态行（文案由 setSleep/cancelSleep/sleepTicker 原样更新），三按钮移入「更多」。
        sleepView = Ui.text(this, "睡眠：关", 12, Ui.MUTED);
        root.addView(sleepView);
        Ui.gap(root, 12);

        spectrumView = new SpectrumView(this);
        spectrumView.setContentDescription("实时频谱");
        spectrumView.setVisibility(spectrumOn ? View.VISIBLE : View.GONE);
        spectrumVisible = spectrumOn;
        root.addView(spectrumView, new LinearLayout.LayoutParams(-1, Ui.dp(this, 96)));
    }

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, 1001);
    }

    /** 0.43.0：SAF 选音频并插到当前曲目之后；request code 与尾部追加的 1001 区分。 */
    private void pickPlayNext() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQ_PLAY_NEXT);
    }

    /** 0.43.0：点击倍速按钮按固定档位循环，更新文案、持久化并立即作用于当前 player。 */
    private void cycleSpeed() {
        speed = PlaybackQueue.SPEEDS[PlaybackQueue.nextSpeedIndex(currentSpeedIndex())];
        if (speedView != null) speedView.setText(speedLabel());
        saveState();
        if (player != null) player.setPlaybackSpeed(speed);
    }

    /** 从 speed 反查最近档位下标；非档位值（如历史持久化的 1.1）先吸附再推进。 */
    private int currentSpeedIndex() {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < PlaybackQueue.SPEEDS.length; i++) {
            float dist = Math.abs(PlaybackQueue.SPEEDS[i] - speed);
            if (dist < bestDist) { bestDist = dist; best = i; }
        }
        return best;
    }

    /** 按钮文案；Float.toString 让 1f/2f 显示 1.0/2.0，0.75f 显示 0.75。 */
    private String speedLabel() { return "速度：" + Float.toString(speed) + "×"; }

    private void pickLyrics() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, 1002);
    }

    /**
     * 音效面板常驻说明用的能力等级：开关代表用户请求；实际生效按「EQ 有非零增益 / 限幅开 /
     * 频谱开」判定，EQ 开而五段全 0 dB 时位透明不算已处理；格式旁路只认 chain 的事实，
     * 无链时按 PCM 处理（那是用户主动全关，不是格式问题）。
     */
    private AudioCapability.Level currentAudioCapabilityLevel() {
        boolean audibleRequested = eqOn || clipOn || spectrumOn;
        boolean audibleActually = clipOn || spectrumOn;
        if (eqOn) {
            for (float gain : eqGains) {
                if (Math.abs(gain) > 1e-6f) { audibleActually = true; break; }
            }
        }
        AudioProcessorChain chain = player == null ? null : player.audioChain();
        boolean formatPcm = chain == null || chain.lastFormatPcm();
        return AudioCapability.classify(formatPcm, audibleRequested, audibleActually);
    }

    /** 阶段 2-A：音效面板（EQ 5 段 + softClip 限幅 + 实时频谱）。 */
    @androidx.media3.common.util.UnstableApi
    private void openSoundPanel() {
        if (debugLog) android.util.Log.d(TAG, "panel=effect action=open");
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 16);
        TextView capabilityNote = Ui.text(this, "", 12, Ui.MUTED);
        String capabilityMessage = AudioCapability.message(currentAudioCapabilityLevel());
        if (capabilityMessage.isEmpty()) capabilityNote.setVisibility(View.GONE);
        else capabilityNote.setText(capabilityMessage);
        panel.addView(capabilityNote);
        panel.addView(Ui.text(this, "均衡器", 11, Ui.MUTED)); // v2.2 分组小标题：不改任何控件行为
        addToggleRow(this, panel, "均衡器（5 段）", eqOn, v -> {
            eqOn = v; applyAudioChain(); saveState();
        });
        String[] names = {"60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz"};
        final SeekBar[] bars = new SeekBar[names.length];
        final TextView[] labels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout row = Ui.row(this);
            labels[i] = Ui.text(this, "", 12, Ui.MUTED);
            labels[i].setMinWidth(Ui.dp(this, 92));
            row.addView(labels[i]);
            SeekBar bar = new SeekBar(this);
            bar.setMax(240); // -12..+12 dB，步进 0.1
            bar.setProgress(Math.round((eqGains[idx] + 12f) * 10f));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar s) { }
                public void onStopTrackingTouch(SeekBar s) { }
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (!fromUser) return;
                    eqGains[idx] = p / 10f - 12f;
                    labels[idx].setText(names[idx] + " " + String.format(Locale.ROOT, "%+.1f dB", eqGains[idx]));
                }
            });
            labels[i].setText(names[idx] + " " + String.format(Locale.ROOT, "%+.1f dB", eqGains[idx]));
            row.addView(bar, Ui.weight());
            panel.addView(row);
            bars[idx] = bar;
        }
        panel.addView(Ui.text(this, "动态与可视化", 11, Ui.MUTED)); // v2.2 分组小标题
        addToggleRow(this, panel, "峰值限幅（softClip）", clipOn, v -> {
            clipOn = v; applyAudioChain(); saveState();
        });
        addToggleRow(this, panel, "实时频谱", spectrumOn, v -> {
            spectrumOn = v;
            spectrumVisible = v;
            if (spectrumView != null) spectrumView.setVisibility(v ? View.VISIBLE : View.GONE);
            saveState();
        });
        panel.addView(Ui.text(this, "音质相关内容需真机听感验证；模拟器无音频输出。", 12, Ui.MUTED));
        new AlertDialog.Builder(this).setTitle("音效").setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (d, w) -> {
                    for (int i = 0; i < bars.length; i++) eqGains[i] = bars[i].getProgress() / 10f - 12f;
                    applyAudioChain();
                    saveState();
                })
                .setOnDismissListener(d -> {
                    if (debugLog) android.util.Log.d(TAG, "panel=effect action=close");
                }).show();
    }

    // ---------- v2.2：一次点按面板与分层菜单 ----------

    /**
     * v2.2 更多菜单：标签与动作成对 append，which 即动态索引，不错配（沿用 v2.1 ReaderActivity 已验证模式）。
     * 倍速、睡眠、导入、插队、停止都收进这里；常驻区只保留播控与三个一次点按面板入口。
     */
    private void openMoreMenu() {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        // 倍速 label 由 speedLabel() 的当前值派生，保持单一真源（形如「倍速：1.0×」）。
        labels.add(speedLabel().replace("速度：", "倍速："));
        actions.add(() -> {
            cycleSpeed();
            Toast.makeText(this, speedLabel(), Toast.LENGTH_SHORT).show(); // 关闭菜单后仍可见新值
        });
        labels.add(sleepLabel()); // 由 sleepView 文案派生，含「N 分钟剩余 / 关」
        actions.add(this::cycleSleepFromMore);
        labels.add("添加音频文件"); actions.add(this::pickAudio);
        labels.add("下一首播放"); actions.add(this::pickPlayNext);
        labels.add("选择歌词(.lrc/.srt/.txt)"); actions.add(this::pickLyrics);
        labels.add("停止并退出"); actions.add(this::finish); // 原 desc「停止播放并退出」语义保留
        new AlertDialog.Builder(this).setTitle("更多播放操作")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                }).setNegativeButton("关闭", null).show();
    }

    /** 睡眠 label 直接读常驻 sleepView 文案；无定时器/关闭态显示「关」，否则显示剩余时间。 */
    private String sleepLabel() {
        String text = sleepView == null ? "" : sleepView.getText().toString();
        String state = text.startsWith("睡眠：") ? text.substring("睡眠：".length()) : text;
        if (state.isEmpty() || state.contains("关")) return "睡眠定时（关）";
        return "睡眠定时（" + state + "剩余）";
    }

    /** 更多菜单里的睡眠项：关→15→30→关，仅复用已有 setSleep/cancelSleep，不碰播放器。 */
    private void cycleSleepFromMore() {
        if (sleepTimer.kind() != SpeechText.SleepTimer.Kind.MINUTES) { setSleep(15); return; }
        long left = sleepTimer.remainingSeconds(SystemClock.elapsedRealtime());
        if (left <= 15 * 60L) setSleep(30);
        else cancelSleep();
    }

    /** 队列按钮 label 随队列长度更新；只读 UI，不改队列顺序、不重建播放器。 */
    private void updateQueueButtonLabel() {
        if (queueButton != null) queueButton.setText("队列 " + queue.size());
    }

    /**
     * v2.2 歌词查看面板：快照式展示，打开时 setText 一次。不在 ticker 里刷新面板内容，
     * 因此不抢用户手动滚动位置；Dialog 只 setView/show，不碰播放器。
     */
    private void openLyricsPanel() {
        if (debugLog) android.util.Log.d(TAG, "panel=lyrics action=open");
        ScrollView scroll = new ScrollView(this);
        TextView content = Ui.text(this, "", 14, Ui.INK);
        Ui.pad(content, 12);
        if (lyrics == null || lyrics.isEmpty()) {
            content.setText("未加载歌词，可在更多→选择歌词导入");
        } else if (lyrics.get(0).timeMs < 0) {
            // 纯文本歌词（TXT）：无时间轴，如实标注不支持按播放位置定位，全量展示。
            StringBuilder sb = new StringBuilder();
            for (Lyrics.Line line : lyrics) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line.text);
            }
            sb.append("\n\n（纯文本歌词无时间轴，不支持按播放位置定位）");
            content.setText(sb.toString());
        } else {
            // 带时间戳：用当前播放位置找最近一句（与常驻 lyricView 同一口径），置顶展示并标注。
            long position = player == null ? 0L : player.position();
            int current = 0;
            for (int i = 0; i < lyrics.size(); i++) {
                if (lyrics.get(i).timeMs <= position) current = i; else break;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("当前句：").append(lyrics.get(current).text).append("\n\n");
            for (int i = 0; i < lyrics.size(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(i == current ? "▶ " : "   ").append(lyrics.get(i).text);
            }
            content.setText(sb.toString());
        }
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("歌词").setView(scroll)
                .setNegativeButton("关闭", null)
                .setOnDismissListener(d -> {
                    if (debugLog) android.util.Log.d(TAG, "panel=lyrics action=close");
                }).show();
    }

    /**
     * v2.2 队列查看面板：只读展示「正在播放 / 接下来」，不改队列顺序、不重建播放器。
     * 空队列提供「添加音频」引导到 pickAudio；队列项不响应点击（插队仍走「下一首播放」）。
     */
    private void openQueuePanel() {
        if (debugLog) android.util.Log.d(TAG, "panel=queue action=open");
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = Ui.column(this);
        Ui.pad(panel, 16);
        if (queue.isEmpty()) {
            panel.addView(Ui.text(this, "队列为空，可添加音频", 13, Ui.MUTED));
            panel.addView(Ui.button(this, "＋ 添加音频", this::pickAudio, "添加音频文件"));
        } else {
            panel.addView(Ui.text(this, "正在播放", 11, Ui.MUTED));
            String playing = currentIndex >= 0 && currentIndex < queue.size()
                    ? displayName(queue.get(currentIndex)) : "（无）";
            panel.addView(Ui.text(this, "▶ " + playing, 14, Ui.INK));
            panel.addView(Ui.text(this, "接下来", 11, Ui.MUTED));
            if (currentIndex + 1 >= queue.size()) {
                panel.addView(Ui.text(this, "队列已到末尾", 12, Ui.MUTED));
            } else {
                for (int i = currentIndex + 1; i < queue.size(); i++) {
                    panel.addView(Ui.text(this, (i - currentIndex) + ". " + displayName(queue.get(i)), 13, Ui.INK));
                }
            }
        }
        scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("播放队列（" + queue.size() + "）").setView(scroll)
                .setNegativeButton("关闭", null)
                .setOnDismissListener(d -> {
                    if (debugLog) android.util.Log.d(TAG, "panel=queue action=close");
                }).show();
    }

    /** 音效面板开关行：点击在开/关间切换并回调。 */
    private interface ToggleListener { void onChanged(boolean on); }

    private void addToggleRow(Context ctx, LinearLayout parent, String label, boolean initial, ToggleListener cb) {
        final boolean[] value = {initial};
        final Button[] holder = new Button[1];
        holder[0] = Ui.button(this, label + "：" + (initial ? "开" : "关"), () -> {
            value[0] = !value[0];
            holder[0].setText(label + "：" + (value[0] ? "开" : "关"));
            cb.onChanged(value[0]);
        }, label);
        parent.addView(holder[0]);
    }

    /** 把当前音效状态推给正在播放的 chain；下一次 queueInput 生效，不重建播放器。 */
    @androidx.media3.common.util.UnstableApi
    private void applyAudioChain() {
        AudioProcessorChain chain = player == null ? null : player.audioChain();
        if (chain != null) {
            chain.setState(eqOn, clipOn, spectrumOn, eqGains);
        } else if (eqOn || clipOn) {
            // 当前 player 无音效链（初始全关时创建）：配置已存，下次播放生效
            Toast.makeText(this, "音效已保存，下次播放生效", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == 1002) {
            String name = lyricsDisplayName(uri);
            if (!supportedLyrics(name)) {
                // 不支持格式：提示并保留当前已加载歌词，不覆盖 customLyricsUri。
                Toast.makeText(this, "不识别该歌词格式（支持 .lrc / .srt / .txt）", Toast.LENGTH_LONG).show();
                return;
            }
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            Lyrics.Doc doc = readLyrics(uri);
            if (doc == null || doc.isEmpty()) {
                Toast.makeText(this, "歌词为空或无法识别，已保留当前歌词", Toast.LENGTH_LONG).show();
                return;
            }
            customLyricsUri = uri;
            lyrics = doc.lines;
            updateLyrics(player != null ? player.position() : 0L);
            Toast.makeText(this, "已载入 " + lyrics.size() + " 行歌词（" + doc.format + "）",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (request == REQ_PLAY_NEXT) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            String value = uri.toString();
            int pos = PlaybackQueue.insertNextIndex(currentIndex, queue.size());
            queue.add(pos, value);
            // 防回归：shuffle 打开时 originalOrder 也必须收录，否则用户关闭 shuffle 时
            // queue.clear()+addAll(originalOrder) 会把刚插队的这一首直接丢弃。
            if (shuffled && originalOrder != null) originalOrder.add(value);
            updateQueueButtonLabel();
            saveState();
            return; // 当前曲目继续播放，不切歌
        }
        if (request != 1001) return;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        queue.add(uri.toString());
        if (currentIndex < 0) currentIndex = queue.size() - 1;
        updateQueueButtonLabel();
        saveState();
        playCurrent();
    }

    // ---------- 播放控制 ----------

    /** 暂停归一化：手动暂停与睡眠到点共用；无论是否在播都清 pausedByFocus，防焦点恢复时自动续播。 */
    private void pausePlayback() {
        pausedByFocus = false; // 到点时即便已被临时焦点暂停，也不能让 AUDIOFOCUS_GAIN 续播
        if (player != null && player.isPlaying()) {
            player.pause();
            abandonFocus();
        }
    }

    private void setSleep(int minutes) {
        sleepTimer.setMinutes(SystemClock.elapsedRealtime(), minutes);
        sleepFired = false;
        if (sleepView != null) sleepView.setText("睡眠：" + minutes + " 分钟");
        main.removeCallbacks(sleepTicker);
        main.post(sleepTicker);
    }

    private void cancelSleep() {
        sleepTimer.off();
        sleepFired = false;
        if (sleepView != null) sleepView.setText("睡眠：关");
        main.removeCallbacks(sleepTicker);
    }

    private void togglePlay() {
        if (player == null) { playCurrent(); return; }
        if (player.isPlaying()) { pausePlayback(); }
        else { sleepFired = false; requestFocus(); player.play(); }
    }

    private void playCurrent() {
        if (sleepFired) { pausePlayback(); return; } // 到点后即使曲目结束也不再播下一首
        releasePlayer();
        // 换曲目后旧计时段作废，避免把上一首的时长记到这一首名下
        audioStatsMark = 0;
        audioStatsSegment = 0;
        if (currentIndex < 0 || currentIndex >= queue.size()) return;
        final String path = currentPath();
        titleView.setText(new File(displayName(path)).getName());
        try {
            // 阶段 2-A：仅当任一音效开启时才挂处理器链；默认全关走原构造，零回归
            AudioProcessorChain chain = (eqOn || clipOn || spectrumOn) ? new AudioProcessorChain() : null;
            OpenLeafPlayer candidate = new OpenLeafPlayer(this, chain);
            if (debugLog) android.util.Log.d(TAG, "create id=" + ++debugInstanceSeq);
            if (chain != null) {
                chain.setState(eqOn, clipOn, spectrumOn, eqGains);
            }
            candidate.setListener(new OpenLeafPlayer.Listener() {
                @Override public void onPrepared() {
                    if (isDestroyed() || player != candidate) return;
                    requestFocus();
                    candidate.startFadeIn();
                    candidate.play();
                    loadLyrics(path);
                }
                @Override public void onCompletion() {
                    if (!isDestroyed() && player == candidate) onTrackEnd();
                }
                @Override public void onError(String reason) {
                    if (!isDestroyed()) Toast.makeText(AudioPlayerActivity.this, "无法播放该音频", Toast.LENGTH_SHORT).show();
                }
                @Override public void onTracksChanged() { }
            });
            player = candidate;
            candidate.setMediaItem(Uri.parse(path), null);
            candidate.setPlaybackSpeed(speed); // 0.43.0：播放器每曲重建，倍速必须重施
            lastPosition = 0;
        } catch (Exception error) {
            Toast.makeText(this, "打开失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onTrackEnd() {
        if (sleepFired) { pausePlayback(); return; } // 到点同时切曲：不再响下一首
        if (repeat == Repeat.ONE) { playCurrent(); return; }
        if (!next() && repeat == Repeat.ALL && !queue.isEmpty()) { currentIndex = 0; playCurrent(); }
    }

    private boolean next() {
        sleepFired = false; // 用户主动切歌：解除到点守卫
        if (queue.isEmpty()) return false;
        currentIndex = (currentIndex + 1) % queue.size();
        saveState(); playCurrent(); return true;
    }

    private void previous() {
        sleepFired = false; // 用户主动切歌：解除到点守卫
        if (queue.isEmpty()) return;
        currentIndex = (currentIndex - 1 + queue.size()) % queue.size();
        saveState(); playCurrent();
    }

    private void toggleShuffle() {
        shuffled = !shuffled;
        if (shuffled) {
            originalOrder = new ArrayList<>(queue);
            String current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
            List<String> rest = new ArrayList<>(queue);
            if (current != null) rest.remove(current);
            Collections.shuffle(rest);
            queue.clear();
            if (current != null) queue.add(current);
            queue.addAll(rest);
            currentIndex = current != null ? 0 : -1;
        } else if (originalOrder != null) {
            String current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
            queue.clear(); queue.addAll(originalOrder);
            currentIndex = current != null ? queue.indexOf(current) : -1;
        }
        shuffleView.setText("随机：" + (shuffled ? "开" : "关"));
        saveState();
    }

    private void toggleRepeat() {
        repeat = repeat == Repeat.NONE ? Repeat.ONE : repeat == Repeat.ONE ? Repeat.ALL : Repeat.NONE;
        repeatView.setText("循环：" + (repeat == Repeat.NONE ? "关" : repeat == Repeat.ONE ? "单曲" : "全部"));
        saveState();
    }

    private void releasePlayer() {
        if (player != null) {
            if (debugLog) android.util.Log.d(TAG, "release id=" + debugInstanceSeq);
            player.release();
            player = null;
        }
    }

    // ---------- 歌词（LRC / 增强 LRC / SRT / TXT，由 core Lyrics 承接） ----------

    private Uri customLyricsUri; // 用户显式选择的歌词（SAF content:// 也可用）

    private void loadLyrics(String audioPath) {
        lyrics = Collections.emptyList();
        if (customLyricsUri != null) {
            // 显式选择的歌词读取失败/为空时保持空，不回退自动推导（选择时已提示）。
            Lyrics.Doc doc = readLyrics(customLyricsUri);
            if (doc != null && !doc.isEmpty()) lyrics = doc.lines;
            return;
        }
        // 自动推导：仅 file:// 有效（同目录同名 .lrc → .srt → .txt）。
        try {
            Uri audio = Uri.parse(audioPath);
            if ("file".equalsIgnoreCase(audio.getScheme())) {
                String path = audio.getPath();
                if (path != null) {
                    String base = path.substring(0, Math.max(0, path.lastIndexOf('.')));
                    for (String extension : new String[]{".lrc", ".srt", ".txt"}) {
                        File file = new File(base + extension);
                        if (!file.isFile()) continue;
                        try (InputStream in = new java.io.FileInputStream(file)) {
                            Lyrics.Doc doc = Lyrics.parse(readAll(in));
                            if (doc != null && !doc.isEmpty()) { lyrics = doc.lines; break; }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    /** 读取 SAF/Uri 歌词；超过 1 MiB 或读取失败返回 null（调用方提示并保留旧歌词）。 */
    private Lyrics.Doc readLyrics(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            byte[] data = readAll(in);
            return data == null ? null : Lyrics.parse(data);
        } catch (Exception failure) {
            return null;
        }
    }

    /** 上限内全量读取；超过 core 的 1 MiB 上限返回 null。 */
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > Lyrics.MAX_BYTES) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 优先读 SAF DISPLAY_NAME，读不到回退 Uri 末段；用于扩展名白名单判断。 */
    private String lyricsDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // 部分提供器不支持 DISPLAY_NAME，回退末段即可。
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private static boolean supportedLyrics(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".lrc") || lower.endsWith(".srt") || lower.endsWith(".txt");
    }

    private void updateLyrics(long position) {
        if (lyrics == null || lyrics.isEmpty()) return;
        if (lyrics.get(0).timeMs < 0) {
            // 静态 TXT：一次显示全部文本，不做时间轴推进。
            StringBuilder text = new StringBuilder();
            for (Lyrics.Line line : lyrics) {
                if (text.length() > 0) text.append('\n');
                text.append(line.text);
            }
            lyricView.setText(text.toString());
            return;
        }
        int index = -1;
        for (int i = 0; i < lyrics.size(); i++) if (lyrics.get(i).timeMs <= position) index = i; else break;
        if (index >= 0) lyricView.setText(lyrics.get(index).text);
    }

    private void updateProgress(long position) {
        if (seeking || player == null) return;
        int duration = (int) player.duration();
        seekBar.setMax(Math.max(1, duration));
        seekBar.setProgress((int) position);
        positionView.setText(time(position) + " / " + time(duration));
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    // ---------- 音频焦点与耳机拔出（借鉴 LocalPlayer，并补其缺失项） ----------

    private void requestFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focusChange).build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(focusChange, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(focusChange);
    }

    private final AudioManager.OnAudioFocusChangeListener focusChange = focus -> {
        if (player == null) return;
        switch (focus) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (duckedByFocus) { player.setVolume(1f); duckedByFocus = false; }
                else if (pausedByFocus) { requestFocus(); player.play(); pausedByFocus = false; }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                if (player.isPlaying()) player.pause();
                pausedByFocus = false; abandonFocus(); break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (player.isPlaying()) { player.pause(); pausedByFocus = true; }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                player.setVolume(0.3f); duckedByFocus = true; break;
            default: break;
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (player != null && player.isPlaying()) { player.pause(); abandonFocus(); }
        }
    };

    private void registerBecomingNoisy() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(noisy, filter);
        becomingNoisyRegistered = true;
    }

    private void setupMediaSession() {
        mediaSession = new android.media.session.MediaSession(this, "OpenLeafAudio");
        mediaSession.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() { if (player != null && !player.isPlaying()) { requestFocus(); player.play(); } }
            @Override public void onPause() { if (player != null && player.isPlaying()) player.pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
        });
        mediaSession.setActive(true);
        showMediaKeyHintOnce();
    }

    /** §6.2 状态 13：媒体键可用时一次性提示；保留框架 MediaSession 以确保 0.13.0 媒体键零回归。 */
    private void showMediaKeyHintOnce() {
        final String key = "media_key_hint_shown";
        android.content.SharedPreferences prefs = getSharedPreferences(PREF, 0);
        if (prefs.getBoolean(key, false)) return;
        prefs.edit().putBoolean(key, true).apply();
        if (mediaKeyHint != null) {
            mediaKeyHint.setText("已启用媒体键控制（耳机/线控可切歌）。");
            mediaKeyHint.setVisibility(View.VISIBLE);
        }
    }

    // ---------- 状态持久化（只存稳定标识，不序列化播放器对象） ----------

    private void restoreState() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREF, 0);
        String saved = prefs.getString(KEY_QUEUE, "");
        if (!saved.isEmpty()) queue.addAll(Arrays.asList(saved.split("\n")));
        currentIndex = prefs.getInt(KEY_INDEX, -1);
        if (currentIndex >= queue.size()) currentIndex = queue.isEmpty() ? -1 : 0;
        try { repeat = Repeat.valueOf(prefs.getString(KEY_REPEAT, Repeat.NONE.name())); }
        catch (IllegalArgumentException ignored) { repeat = Repeat.NONE; }
        shuffled = prefs.getBoolean(KEY_SHUFFLE, false);
        speed = PlaybackQueue.clampSpeed(prefs.getFloat(KEY_SPEED, 1f));
        // 阶段 2-A：默认全部关闭，零回归
        eqOn = prefs.getBoolean(KEY_EQ_ON, false);
        clipOn = prefs.getBoolean(KEY_CLIP_ON, false);
        spectrumOn = prefs.getBoolean(KEY_SPECTRUM_ON, false);
        String gains = prefs.getString(KEY_EQ_GAINS, "");
        float[] parsed = org.openleaf.reader.core.AudioEq.clampGains(
                gains.isEmpty() ? null : parseGains(gains));
        System.arraycopy(parsed, 0, eqGains, 0, eqGains.length);
    }

    private static float[] parseGains(String raw) {
        String[] parts = raw.split(",");
        float[] out = new float[org.openleaf.reader.core.AudioEq.BANDS.length];
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try { out[i] = Float.parseFloat(parts[i]); } catch (NumberFormatException ignored) { out[i] = 0f; }
        }
        return out;
    }

    private static String formatGains(float[] gains) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(gains[i]));
        }
        return sb.toString();
    }

    private void saveState() {
        getSharedPreferences(PREF, 0).edit()
                .putString(KEY_QUEUE, String.join("\n", queue))
                .putInt(KEY_INDEX, currentIndex)
                .putString(KEY_REPEAT, repeat.name())
                .putBoolean(KEY_SHUFFLE, shuffled)
                .putFloat(KEY_SPEED, speed)
                .putBoolean(KEY_EQ_ON, eqOn)
                .putBoolean(KEY_CLIP_ON, clipOn)
                .putBoolean(KEY_SPECTRUM_ON, spectrumOn)
                .putString(KEY_EQ_GAINS, formatGains(eqGains))
                .apply();
    }

    private String currentPath() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : "";
    }

    private static String displayName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
