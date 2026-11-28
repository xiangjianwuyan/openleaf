package org.openleaf.reader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.openleaf.reader.core.MediaSessionIdentity;
import org.openleaf.reader.core.TextIndexSpec;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 听书界面：只负责显示与下命令，朗读本身在 {@link TtsPlaybackService} 里。
 *
 * <p>这一层被刻意做薄——上一版把 {@code TextToSpeech} 直接放在 Activity，一退出页面就
 * {@code shutdown()}，于是「锁屏即停」。现在退出界面朗读照常，通知栏可以控制，
 * 回来时从这里接上服务当前状态即可。
 */
public final class TtsActivity extends Activity implements TtsPlaybackService.Listener {

    /** 从阅读页直接传入选中文本。 */
    public static final String EXTRA_SPEAK_TEXT = "openleaf.speakText";

    private TextView titleView, chapterView, sentenceView, sleepView;
    private android.widget.Button playButton;
    private SeekBar speedBar;
    /** 渲染快照时回填语速滑杆，避免触发监听又反过来下命令。 */
    private boolean bindingSpeed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        TtsPlaybackService.addListener(this);
        dispatch(getIntent());
        requestNotificationPermission();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatch(intent);
    }

    /** 按入参决定：书库选书 / 选中文本 / 只是回来看看当前状态。 */
    private void dispatch(Intent intent) {
        String bookId = intent == null ? null : intent.getStringExtra(TtsPlaybackService.EXTRA_BOOK_ID);
        String text = intent == null ? null : intent.getStringExtra(EXTRA_SPEAK_TEXT);
        if (bookId != null && !bookId.isEmpty()) {
            int chapter = intent.getIntExtra(TtsPlaybackService.EXTRA_CHAPTER, 0);
            TextIndexSpec spec = TextIndexRegistry.claimedFrom(intent);
            if (spec != null) TtsPlaybackService.playBook(this, bookId, chapter, spec);
            else TtsPlaybackService.playBook(this, bookId, chapter);
        } else if (text != null && !text.trim().isEmpty()) {
            TtsPlaybackService.playText(this, text);
        }
        render(TtsPlaybackService.current());
    }

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        root.addView(Ui.title(this, "听书", 22));
        titleView = Ui.text(this, "", 15, Ui.INK);
        root.addView(titleView);
        chapterView = Ui.text(this, "未选择书籍", 13, Ui.MUTED);
        root.addView(chapterView);
        Ui.gap(root, 8);
        sentenceView = Ui.text(this, "点击「朗读」开始", 17, Ui.INK);
        sentenceView.setMinLines(3);
        root.addView(sentenceView);
        Ui.gap(root, 12);

        LinearLayout sentenceRow = Ui.row(this);
        sentenceRow.addView(Ui.button(this, "⏮ 上一句",
                () -> TtsPlaybackService.command(this, TtsPlaybackService.ACTION_PREV_SENTENCE)));
        playButton = Ui.button(this, "▶ 朗读",
                () -> TtsPlaybackService.command(this, TtsPlaybackService.ACTION_TOGGLE));
        sentenceRow.addView(playButton);
        sentenceRow.addView(Ui.button(this, "⏭ 下一句",
                () -> TtsPlaybackService.command(this, TtsPlaybackService.ACTION_NEXT_SENTENCE)));
        root.addView(sentenceRow);

        LinearLayout chapterRow = Ui.row(this);
        chapterRow.addView(Ui.button(this, "上一章",
                () -> TtsPlaybackService.command(this, TtsPlaybackService.ACTION_PREV_CHAPTER)));
        chapterRow.addView(Ui.button(this, "下一章",
                () -> TtsPlaybackService.command(this, TtsPlaybackService.ACTION_NEXT_CHAPTER)));
        chapterRow.addView(Ui.button(this, MediaSessionIdentity.TTS.stopLabel(),
                () -> TtsPlaybackService.command(this, TtsPlaybackService.ACTION_STOP)));
        root.addView(chapterRow);

        speedBar = new SeekBar(this);
        speedBar.setMax(TtsPlaybackService.RATES.length - 1);
        speedBar.setProgress(1);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!bindingSpeed && fromUser) TtsPlaybackService.setSpeed(TtsActivity.this, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(speedBar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(Ui.text(this, "语速 0.75× — 2×", 12, Ui.MUTED));

        root.addView(Ui.text(this, "定时对象：" + MediaSessionIdentity.TTS.objectLabel()
                + "（与本地音频是不同会话，互不影响）。", 12, Ui.MUTED));
        LinearLayout sleepRow = Ui.row(this);
        sleepRow.addView(Ui.button(this, "睡眠 15 分", () -> TtsPlaybackService.setSleep(this, 15)));
        sleepRow.addView(Ui.button(this, "睡眠 30 分", () -> TtsPlaybackService.setSleep(this, 30)));
        sleepRow.addView(Ui.button(this, "本章结束", () -> TtsPlaybackService.setSleepChapterEnd(this)));
        root.addView(sleepRow);
        sleepView = Ui.text(this, "睡眠：关", 12, Ui.MUTED);
        root.addView(sleepView);
        Ui.gap(root, 12);
        root.addView(Ui.text(this, "退出本页朗读不会中断；锁屏后仍会继续，可在通知栏控制。", 12, Ui.MUTED));
        Ui.gap(root, 8);
        root.addView(Ui.button(this, "选择 TXT 文件", this::pickBook));
        root.addView(Ui.button(this, "返回", this::finish));
    }

    private void pickBook() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, 2001);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 2001 || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            String text = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in != null) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) { }
            final String content = text;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (content == null || content.trim().isEmpty()) {
                    Toast.makeText(this, "无法读取该文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                TtsPlaybackService.playText(this, content);
            });
        }).start();
    }

    /** API 33 起前台服务通知需要运行时授权；被拒绝也不影响朗读，只是通知栏看不到。 */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3001);
    }

    @Override public void onTtsState(final TtsPlaybackService.Snapshot snapshot) {
        runOnUiThread(() -> { if (!isDestroyed()) render(snapshot); });
    }

    private void render(TtsPlaybackService.Snapshot snapshot) {
        if (snapshot == null) return;
        if (snapshot.preparing) {
            titleView.setText("正在准备…");
        } else if (!snapshot.hasSource) {
            titleView.setText("");
        } else {
            titleView.setText(snapshot.title);
        }
        chapterView.setText(snapshot.hasSource ? snapshot.chapterLabel : "未选择书籍");
        sentenceView.setText(snapshot.sentence == null || snapshot.sentence.isEmpty()
                ? "点击「朗读」开始" : snapshot.sentence);
        playButton.setText(snapshot.playing ? "⏸ 暂停" : "▶ 朗读");
        sleepView.setText(snapshot.sleepLabel);
        if (speedBar.getProgress() != snapshot.speedIndex) {
            bindingSpeed = true;
            speedBar.setProgress(Math.max(0, Math.min(TtsPlaybackService.RATES.length - 1, snapshot.speedIndex)));
            bindingSpeed = false;
        }
    }

    @Override protected void onDestroy() {
        TtsPlaybackService.removeListener(this);
        super.onDestroy();
    }
}
