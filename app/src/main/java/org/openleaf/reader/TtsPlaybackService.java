package org.openleaf.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.openleaf.reader.core.LazyChapterBook;
import org.openleaf.reader.core.ProgressModel;
import org.openleaf.reader.core.ReadingAnchor;
import org.openleaf.reader.core.ReadingLedger;
import org.openleaf.reader.core.SpeechText;
import org.openleaf.reader.core.TextIndexSpec;
import org.openleaf.reader.core.TtsPlaybackPlan;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 听书前台服务：把 {@link TextToSpeech} 从界面里搬出来，锁屏、切后台、退出界面都继续朗读。
 *
 * <p>三件在 Activity 里做不到的事：
 * <ul>
 *   <li>前台服务 + 通知栏控制：离开界面不被回收，通知栏可直接暂停/换章；</li>
 *   <li>PARTIAL_WAKE_LOCK：锁屏后 CPU 不休眠。TTS 引擎自己会出声，但「一句讲完推进下一句」
 *       的回调跑在本进程，进程被冻结就会停在两句之间——这才是「锁屏几分钟就没声」的真因；</li>
 *   <li>音频焦点：来电/别的应用抢焦点时暂停，而不是跟人抢着念。</li>
 * </ul>
 *
 * <p>正文按章懒加载：{@link LazyChapterBook} 一次只载入当前章并切句，整本书不进内存。
 * 进度写回与阅读页同一套 prefs 键（{@code chapter_/offset_/anchor_/progress_}），
 * 所以「听一段→回阅读页」能接在原句上。
 */
public final class TtsPlaybackService extends Service {

    public static final String ACTION_START_BOOK = "org.openleaf.reader.tts.START_BOOK";
    public static final String ACTION_START_TEXT = "org.openleaf.reader.tts.START_TEXT";
    public static final String ACTION_TOGGLE = "org.openleaf.reader.tts.TOGGLE";
    public static final String ACTION_PREV_SENTENCE = "org.openleaf.reader.tts.PREV_SENTENCE";
    public static final String ACTION_NEXT_SENTENCE = "org.openleaf.reader.tts.NEXT_SENTENCE";
    public static final String ACTION_PREV_CHAPTER = "org.openleaf.reader.tts.PREV_CHAPTER";
    public static final String ACTION_NEXT_CHAPTER = "org.openleaf.reader.tts.NEXT_CHAPTER";
    public static final String ACTION_SET_SPEED = "org.openleaf.reader.tts.SET_SPEED";
    public static final String ACTION_SET_SLEEP = "org.openleaf.reader.tts.SET_SLEEP";
    public static final String ACTION_STOP = "org.openleaf.reader.tts.STOP";

    public static final String EXTRA_BOOK_ID = "bookId";
    public static final String EXTRA_CHAPTER = "chapter";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_SPEED_INDEX = "speedIndex";
    /** 睡眠分钟数；0 表示关闭。 */
    public static final String EXTRA_SLEEP_MINUTES = "sleepMinutes";
    public static final String EXTRA_SLEEP_CHAPTER_END = "sleepChapterEnd";

    /** 语速档位，界面与服务共用同一组值。 */
    public static final float[] RATES = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final String PREFS = "reader";
    private static final String CHANNEL = "tts_playback";
    /** 插桩要按这个 id 找活跃通知，因此不设 private。 */
    static final int NOTIFICATION_ID = 4101;
    private static final long STATS_CHUNK_MS = 60_000L;

    // ---- 对界面暴露的只读状态（同进程静态，避免 bind 与 Binder 的样板） ----

    /** 界面渲染所需的一次快照。 */
    public static final class Snapshot {
        public final boolean playing, ready, preparing, hasSource;
        public final String title, chapterLabel, sentence, sleepLabel;
        public final int chapter, chapters, index, count, speedIndex;

        Snapshot(boolean playing, boolean ready, boolean preparing, boolean hasSource,
                 String title, String chapterLabel, String sentence, String sleepLabel,
                 int chapter, int chapters, int index, int count, int speedIndex) {
            this.playing = playing;
            this.ready = ready;
            this.preparing = preparing;
            this.hasSource = hasSource;
            this.title = title;
            this.chapterLabel = chapterLabel;
            this.sentence = sentence;
            this.sleepLabel = sleepLabel;
            this.chapter = chapter;
            this.chapters = chapters;
            this.index = index;
            this.count = count;
            this.speedIndex = speedIndex;
        }
    }

    private static final Snapshot EMPTY = new Snapshot(false, false, false, false,
            "", "", "点击「朗读」开始", "睡眠：关", -1, 0, -1, 0, 1);

    public interface Listener { void onTtsState(Snapshot snapshot); }

    private static volatile Snapshot snapshot = EMPTY;
    /** 当前服务里载入的书；供阅读页判断是否仍在后台写同一本书的位置。 */
    private static volatile String activeBookId;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static Snapshot current() { return snapshot; }

    /** 服务当前是否载入着这本书（含暂停态）；阅读页据此决定是否停播以消除位置双写。 */
    public static boolean isPlayingBook(String id) {
        return id != null && id.equals(activeBookId);
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    // ---- 启动入口 ----

    public static void playBook(Context context, String bookId, int chapter) {
        start(context, new Intent(context, TtsPlaybackService.class)
                .setAction(ACTION_START_BOOK)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_CHAPTER, Math.max(0, chapter)));
    }

    /**
     * 带文本索引 spec 起播：只把 spec 序列化进 intent 透传，开书/会话快照留给 T04。
     * 原 3 参重载逐字不变，未携带 spec 的旧路径继续走默认索引。
     */
    public static void playBook(Context context, String bookId, int chapter, TextIndexSpec spec) {
        Intent intent = new Intent(context, TtsPlaybackService.class)
                .setAction(ACTION_START_BOOK)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_CHAPTER, Math.max(0, chapter));
        TextIndexRegistry.putInto(intent, spec);
        start(context, intent);
    }

    public static void playText(Context context, String text) {
        start(context, new Intent(context, TtsPlaybackService.class)
                .setAction(ACTION_START_TEXT).putExtra(EXTRA_TEXT, text));
    }

    public static void command(Context context, String action) {
        start(context, new Intent(context, TtsPlaybackService.class).setAction(action));
    }

    public static void setSpeed(Context context, int speedIndex) {
        start(context, new Intent(context, TtsPlaybackService.class)
                .setAction(ACTION_SET_SPEED).putExtra(EXTRA_SPEED_INDEX, speedIndex));
    }

    public static void setSleep(Context context, int minutes) {
        start(context, new Intent(context, TtsPlaybackService.class)
                .setAction(ACTION_SET_SLEEP).putExtra(EXTRA_SLEEP_MINUTES, minutes)
                .putExtra(EXTRA_SLEEP_CHAPTER_END, false));
    }

    public static void setSleepChapterEnd(Context context) {
        start(context, new Intent(context, TtsPlaybackService.class)
                .setAction(ACTION_SET_SLEEP).putExtra(EXTRA_SLEEP_MINUTES, 0)
                .putExtra(EXTRA_SLEEP_CHAPTER_END, true));
    }

    private static void start(Context context, Intent intent) {
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException unavailable) {
            // 某些定制 ROM 不允许从后台起前台服务；退化成普通启动，服务内部仍会尝试进前台
            try { context.startService(intent); } catch (RuntimeException ignored) { }
        }
    }

    // ---- 运行态 ----

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SpeechText.Generation generation = new SpeechText.Generation();
    private final SpeechText.SleepTimer sleep = new SpeechText.SleepTimer();
    private final Runnable sleepTicker = new Runnable() {
        @Override public void run() {
            if (sleep.kind() == SpeechText.SleepTimer.Kind.MINUTES) {
                long left = sleep.remainingSeconds(SystemClock.elapsedRealtime());
                if (sleep.expired(SystemClock.elapsedRealtime())) {
                    pause();
                    sleep.off();
                    updateNotification();
                    notifyListeners();
                } else {
                    notifyListeners();
                }
            }
            // 只有定时睡眠需要每秒刷新；关掉后不再自转，避免播放期间空转 1Hz
            if (sleep.kind() == SpeechText.SleepTimer.Kind.MINUTES) main.postDelayed(this, 1000);
        }
    };

    private TextToSpeech tts;
    private boolean ready;
    private boolean playing;
    private boolean preparing;
    private Source source;
    private String bookId;
    private int chapter = -1;
    private int index = -1;
    private List<SpeechText.Passage> passages = Collections.emptyList();
    private int speedIndex = 1;
    private long statsMark;
    private long statsSegment;

    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post(this::pause);
        }
    };

    /** 正文来源：书库里的书，或一次性传入的文本。 */
    private interface Source {
        int chapters();
        String title();
        String chapterLabel(int chapter);
        /** 载入该章并切句；失败抛 IOException。 */
        List<SpeechText.Passage> load(int chapter) throws IOException;
        /** 书库来源才有（用于进度回写与百分比）；文本来源返回 null。 */
        LazyChapterBook book();
        ProgressModel progress();
        void close();
    }

    @Override public void onCreate() {
        super.onCreate();
        resetStatics();
        ensureChannel();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenLeaf:tts");
            wakeLock.setReferenceCounted(false);
        }
        mediaSession = new MediaSession(this, "OpenLeafTts");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { main.post(TtsPlaybackService.this::resume); }
            @Override public void onPause() { main.post(TtsPlaybackService.this::pause); }
            @Override public void onSkipToNext() { main.post(TtsPlaybackService.this::nextChapter); }
            @Override public void onSkipToPrevious() { main.post(TtsPlaybackService.this::previousChapter); }
            @Override public void onStop() { main.post(TtsPlaybackService.this::stopPlayback); }
        });
        mediaSession.setActive(true);
        tts = new TextToSpeech(this, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS) {
                preparing = false;
                notifyListeners();
                return;
            }
            int result = tts.setLanguage(Locale.CHINA);
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            setupUtteranceListener();
            notifyListeners();
            // 语音引擎就绪晚于正文载入时，补上第一句
            if (ready && index >= 0 && !playing && !preparing) speakCurrent(generation.next());
        }));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先进入前台：startForegroundService 之后 5 秒内不调 startForeground，
        // 系统会抛 ForegroundServiceDidNotStartInTimeException。放在动作分发之前最安全。
        enterForeground();
        String action = intent == null ? null : intent.getAction();
        if (action == null) return START_NOT_STICKY;
        switch (action) {
            case ACTION_START_BOOK:
                openBook(intent.getStringExtra(EXTRA_BOOK_ID),
                        Math.max(0, intent.getIntExtra(EXTRA_CHAPTER, 0)),
                        TextIndexRegistry.claimedFrom(intent));
                break;
            case ACTION_START_TEXT:
                openText(intent.getStringExtra(EXTRA_TEXT));
                break;
            case ACTION_TOGGLE:
                if (playing) pause(); else resume();
                break;
            case ACTION_PREV_SENTENCE: previousSentence(); break;
            case ACTION_NEXT_SENTENCE: nextSentence(); break;
            case ACTION_PREV_CHAPTER: previousChapter(); break;
            case ACTION_NEXT_CHAPTER: nextChapter(); break;
            case ACTION_SET_SPEED:
                speedIndex = Math.max(0, Math.min(RATES.length - 1,
                        intent.getIntExtra(EXTRA_SPEED_INDEX, speedIndex)));
                if (playing) speakCurrent(generation.next());
                notifyListeners();
                break;
            case ACTION_SET_SLEEP:
                if (intent.getBooleanExtra(EXTRA_SLEEP_CHAPTER_END, false)) {
                    sleep.setChapterEnd();
                } else {
                    int minutes = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0);
                    if (minutes <= 0) sleep.off();
                    else sleep.setMinutes(SystemClock.elapsedRealtime(), minutes);
                }
                main.removeCallbacks(sleepTicker);
                main.post(sleepTicker);
                updateNotification();
                notifyListeners();
                break;
            case ACTION_STOP:
                stopPlayback();
                return START_NOT_STICKY;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---- 载入 ----

    private void openBook(String id, int startChapter) {
        openBook(id, startChapter, null);
    }

    /** 带 spec 开书：规则/编码按 spec 注入；无 spec 时 3 参重载回落原默认调用。 */
    private void openBook(String id, int startChapter, TextIndexSpec spec) {
        if (id == null || id.isEmpty()) { pause(); notifyListeners(); return; }
        preparing = true;
        playing = false;
        notifyListeners();
        updateNotification();
        final int requested = Math.max(0, startChapter);
        io.execute(() -> {
            Source opened = null;
            List<SpeechText.Passage> first = null;
            int at = requested;
            try {
                BookStore store = new BookStore(this);
                BookStore.Book record = store.find(id);
                if (record == null) throw new IOException("书库里找不到这本书");
                BookAccess access = store.open(record);
                LazyChapterBook book = null;
                try {
                    if (spec == null) {
                        book = LazyChapterBook.open(access.file, record.format, getCacheDir());
                    } else {
                        book = LazyChapterBook.open(access.file, record.format, getCacheDir(),
                                TextIndexRegistry.ruleOf(spec), TextIndexRegistry.charsetOf(spec, access.file));
                    }
                    opened = new BookSource(record, access, book);
                } catch (Exception failed) {
                    if (book != null) book.close();
                    access.close();
                    throw failed;
                }
                at = Math.max(0, Math.min(requested, Math.max(0, book.size() - 1)));
                first = opened.load(at);
            } catch (Exception failed) {
                if (opened != null) opened.close();
                opened = null;
                first = null;
            }
            final Source loaded = opened;
            final List<SpeechText.Passage> passagesOf = first;
            final int chapterAt = at;
            main.post(() -> {
                preparing = false;
                if (loaded == null || passagesOf == null) {
                    pause();
                    notifyListeners();
                    updateNotification();
                    return;
                }
                closeSource();
                source = loaded;
                bookId = id;
                activeBookId = id;
                chapter = chapterAt;
                passages = passagesOf;
                index = TtsPlaybackPlan.resolve(0, passages.size());
                statsMark = 0;
                notifyListeners();
                updateNotification();
                if (ready) speakCurrent(generation.next());
            });
        });
    }

    private void openText(String text) {
        if (text == null || text.trim().isEmpty()) { notifyListeners(); return; }
        preparing = false;
        closeSource();
        source = new TextSource(text.trim());
        bookId = null;
        chapter = 0;
        try {
            passages = source.load(0);
        } catch (IOException failed) {
            passages = Collections.emptyList();
        }
        index = TtsPlaybackPlan.resolve(0, passages.size());
        statsMark = 0;
        notifyListeners();
        updateNotification();
        if (ready && index >= 0) speakCurrent(generation.next());
    }

    // ---- 播放 ----

    private void resume() {
        if (passages.isEmpty() || index < 0) { notifyListeners(); return; }
        if (!requestFocus()) { notifyListeners(); return; }
        wake();
        speakCurrent(generation.next());
    }

    /** 朗读当前句；utterance id 编码世代，迟到回调由代际过滤掉。 */
    private void speakCurrent(long tag) {
        if (!ready) { notifyListeners(); return; }
        if (index < 0 || index >= passages.size()) { pause(); return; }
        SpeechText.Passage passage = passages.get(index);
        playing = true;
        tts.setSpeechRate(RATES[Math.max(0, Math.min(RATES.length - 1, speedIndex))]);
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        tts.speak(passage.text, TextToSpeech.QUEUE_FLUSH, params, "ol:" + tag + ":" + index);
        persistPosition(passage);
        tickStats();
        // 不用 Handler.hasCallbacks（API 29）：直接重置一次 ticker，重复 post 由 removeCallbacks 兜住
        main.removeCallbacks(sleepTicker);
        main.postDelayed(sleepTicker, 1000);
        updateNotification();
        notifyListeners();
    }

    private void pause() {
        playing = false;
        generation.next();          // 废弃在途 utterance，防止 stop 之后还接着念
        if (tts != null && ready) tts.stop();
        abandonFocus();
        releaseWake();
        updateNotification();
        notifyListeners();
    }

    private void setupUtteranceListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) {
                main.post(() -> {
                    if (!belongsToCurrentGeneration(utteranceId)) return;
                    TtsPlaybackPlan.Step next = TtsPlaybackPlan.after(
                            source == null ? 0 : source.chapters(), chapter, index, passages.size());
                    boolean chapterEnd = next == null || next.chapterChanged;
                    if (sleep.shouldStopAtChapterEnd(chapterEnd)) {
                        pause();
                        sleep.off();
                        updateNotification();
                        notifyListeners();
                        return;
                    }
                    if (next == null) { pause(); return; }
                    if (!next.chapterChanged) {
                        index = TtsPlaybackPlan.resolve(next.index, passages.size());
                        speakCurrent(generation.current());
                    } else {
                        loadChapter(next.chapter, next.index);
                    }
                });
            }
            @Override public void onError(String utteranceId) {
                // stop/seek 之后迟到的错误回调不能推进位置，也不能当失败上报
                main.post(() -> { if (belongsToCurrentGeneration(utteranceId)) pause(); });
            }
        });
    }

    private boolean belongsToCurrentGeneration(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith("ol:")) return false;
        String[] parts = utteranceId.split(":");
        try { return Long.parseLong(parts[1]) == generation.current(); }
        catch (RuntimeException invalid) { return false; }
    }

    /** 跨章：后台载入该章正文，回到主线程接上。 */
    private void loadChapter(int target, int targetIndex) {
        final Source current = source;
        if (current == null) return;
        io.execute(() -> {
            List<SpeechText.Passage> loaded;
            try {
                loaded = current.load(target);
            } catch (IOException failed) {
                main.post(() -> { pause(); notifyListeners(); });
                return;
            }
            final List<SpeechText.Passage> text = loaded;
            main.post(() -> {
                if (source != current) return;   // 载入途中用户换了书，丢弃
                chapter = target;
                passages = text;
                index = TtsPlaybackPlan.resolve(targetIndex, passages.size());
                if (index < 0) { pause(); return; }
                speakCurrent(generation.next());
            });
        });
    }

    private void nextSentence() {
        if (index >= 0 && index + 1 < passages.size()) {
            index++;
            speakCurrent(generation.next());
        } else {
            nextChapter();
        }
    }

    private void previousSentence() {
        if (index > 0) {
            index--;
            speakCurrent(generation.next());
        } else {
            previousChapter();
        }
    }

    private void nextChapter() {
        if (source == null) return;
        TtsPlaybackPlan.Step step = TtsPlaybackPlan.nextChapter(source.chapters(), chapter);
        if (step == null) { pause(); return; }
        loadChapter(step.chapter, 0);
    }

    private void previousChapter() {
        if (source == null) return;
        TtsPlaybackPlan.Step step = TtsPlaybackPlan.previousChapter(source.chapters(), chapter);
        if (step == null) { index = 0; speakCurrent(generation.next()); return; }
        loadChapter(step.chapter, TtsPlaybackPlan.LAST_PASSAGE);
    }

    // ---- 进度与统计 ----

    /**
     * 每句开始都把位置写回阅读页的那套键。只写 {@code chapter_/offset_} 不够：
     * 阅读页优先用 {@code anchor_} 还原，若不同步更新，听完再回阅读页会跳回听之前的旧位置。
     */
    private void persistPosition(SpeechText.Passage passage) {
        LazyChapterBook book;
        ProgressModel progress;
        try {
            book = source == null ? null : source.book();
            progress = source == null ? null : source.progress();
        } catch (RuntimeException unavailable) { return; }
        writePosition(this, bookId, book, chapter, passage.offset, progress);
    }

    /**
     * 把「听到第几章第几个字」写回阅读页的那套 prefs 键。抽成静态是为了让插桩能直接
     * 验证这份契约（服务要真跑起来需要语音引擎，验证写入格式不该被引擎可用性绑架）。
     *
     * <p>只写 {@code chapter_/offset_} 不够：阅读页优先用 {@code anchor_} 还原，
     * 若不同步更新，听完再回阅读页会跳回听之前的旧位置。
     */
    static void writePosition(Context context, String bookId, LazyChapterBook book,
                              int chapter, int offset, ProgressModel progress) {
        if (context == null || bookId == null || book == null) return;
        if (chapter < 0 || chapter >= book.size()) return;
        try {
            LazyChapterBook.Section section = book.meta(chapter);
            int effective = Math.max(0, Math.min(offset, section.charCount));
            long sourceOffset = section.sourceStart >= 0 ? section.sourceStart + effective : -1;
            ReadingAnchor anchor = ReadingAnchor.text(section.chapterKey, chapter, section.spineIndex,
                    sourceOffset, effective, false, 0f);
            SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, 0).edit()
                    .putInt("chapter_" + bookId, chapter)
                    .putInt("offset_" + bookId, effective)
                    .putBoolean("bottom_" + bookId, false)
                    .putFloat("lineFraction_" + bookId, 0f)
                    .putString("anchor_" + bookId, anchor.serialize());
            if (progress != null) {
                editor.putInt("progress_" + bookId, progress.percent(chapter, effective, false));
            }
            if (section.sourceStart >= 0) editor.putInt("text_layout_version_" + bookId, 1);
            // 给阅读页一个「这是外部写入」的信号；阅读页 onResume 看到修订号变了才重定位，
            // 不会把自己保存的位置误判成外部变更。
            editor.putString(org.openleaf.reader.core.PositionSync.revisionKey(bookId),
                    org.openleaf.reader.core.PositionSync.newRevision());
            editor.apply();
        } catch (RuntimeException ignored) {
            // 位置写失败不该中断朗读
        }
    }

    /** 听书时长计入 AUDIO 分列；按整分钟分段落库，避免频繁写库。 */
    private void tickStats() {
        long now = SystemClock.elapsedRealtime();
        if (statsMark <= 0) { statsMark = now; statsSegment = now; return; }
        long elapsed = now - statsMark;
        if (elapsed < STATS_CHUNK_MS) return;
        statsMark = now;
        if (bookId == null) return;
        long seconds = elapsed / 1000;
        try {
            new ReadingStatsStore(this).addSeconds(bookId, seconds, ReadingLedger.Kind.AUDIO, statsSegment);
        } catch (RuntimeException ignored) {
            // 统计失败不影响播放
        }
        statsSegment = now;
    }

    // ---- 音频焦点 / 休眠锁 ----

    private boolean requestFocus() {
        if (audioManager == null) return true;
        if (focusRequest == null) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .setWillPauseWhenDucked(true)
                    .build();
        }
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
    }

    private void wake() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---- 通知 ----

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "听书播放", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private void enterForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        try { manager.notify(NOTIFICATION_ID, buildNotification()); }
        catch (RuntimeException ignored) { }
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, TtsPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, TtsActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = source == null ? "听书" : source.title();
        String detail;
        if (preparing) detail = "正在准备…";
        else if (source == null) detail = "未选择书籍";
        else if (chapter >= 0 && !passages.isEmpty()) detail = source.chapterLabel(chapter);
        else detail = "已暂停";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_leaf)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                        "上一章", action(ACTION_PREV_CHAPTER, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, playing
                                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        playing ? "暂停" : "播放", action(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_next),
                        "下一章", action(ACTION_NEXT_CHAPTER, 3)).build());
        return builder.build();
    }

    // ---- 状态广播 ----

    private void notifyListeners() {
        if (mediaSession != null) {
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            Math.max(0, index), playing ? 1f : 0f)
                    .build();
            mediaSession.setPlaybackState(state);
        }
        String label = "";
        if (source != null && chapter >= 0) label = source.chapterLabel(chapter);
        String sentence = (index >= 0 && index < passages.size())
                ? passages.get(index).text : (playing ? "" : "已暂停");
        Snapshot next = new Snapshot(playing, ready, preparing, source != null,
                source == null ? "" : source.title(), label, sentence, sleepLabel(),
                chapter, source == null ? 0 : source.chapters(), index, passages.size(), speedIndex);
        snapshot = next;
        for (Listener listener : LISTENERS) {
            main.post(() -> {
                try { listener.onTtsState(next); } catch (RuntimeException ignored) { }
            });
        }
    }

    private String sleepLabel() {
        if (sleep.kind() == SpeechText.SleepTimer.Kind.MINUTES) {
            long left = Math.max(0, sleep.remainingSeconds(SystemClock.elapsedRealtime()));
            return "睡眠：" + (left / 60) + ":" + String.format(Locale.ROOT, "%02d", left % 60);
        }
        if (sleep.kind() == SpeechText.SleepTimer.Kind.CHAPTER_END) return "睡眠：本章结束";
        return "睡眠：关";
    }

    /** 供界面/插桩重置静态快照（进程内单例，服务重启时要清）。 */
    private static void resetStatics() {
        snapshot = new Snapshot(false, false, false, false, "", "", "点击「朗读」开始", "睡眠：关",
                -1, 0, -1, 0, 1);
    }

    private void closeSource() {
        if (source != null) {
            try { source.close(); } catch (RuntimeException ignored) { }
            source = null;
        }
    }

    private void stopPlayback() {
        pause();
        closeSource();
        passages = Collections.emptyList();
        index = -1;
        chapter = -1;
        bookId = null;
        activeBookId = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(sleepTicker);
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (RuntimeException ignored) { }
            tts = null;
        }
        closeSource();
        abandonFocus();
        releaseWake();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        io.shutdownNow();
        activeBookId = null;
        resetStatics();
        super.onDestroy();
    }

    // ---- 两种正文来源 ----

    /** 书库里的书：按章懒加载，带进度与百分比。 */
    private static final class BookSource implements Source {
        private final BookStore.Book record;
        private final BookAccess access;
        private final LazyChapterBook book;
        private final ProgressModel progress;

        BookSource(BookStore.Book record, BookAccess access, LazyChapterBook book) {
            this.record = record;
            this.access = access;
            this.book = book;
            this.progress = new ProgressModel(book);
        }

        @Override public int chapters() { return book.size(); }
        @Override public String title() { return record.title == null ? "听书" : record.title; }
        @Override public String chapterLabel(int chapter) {
            if (chapter < 0 || chapter >= book.size()) return "";
            String name = book.meta(chapter).title;
            return name == null || name.trim().isEmpty() ? ("第 " + (chapter + 1) + " 章") : name;
        }

        @Override public List<SpeechText.Passage> load(int chapter) throws IOException {
            if (chapter < 0 || chapter >= book.size()) return Collections.emptyList();
            String text = book.load(chapter);
            if (text == null || text.isEmpty()) return Collections.emptyList();
            List<SpeechText.Block> blocks = Collections.singletonList(new SpeechText.Block(0, text, false));
            List<SpeechText.Block> spoken = SpeechText.spokenBlocks(blocks);
            return SpeechText.sentences(spoken);
        }

        @Override public LazyChapterBook book() { return book; }
        @Override public ProgressModel progress() { return progress; }

        @Override public void close() {
            try { book.close(); } catch (RuntimeException ignored) { }
            try { access.close(); } catch (Exception ignored) { }
        }
    }

    /** 一次性文本（阅读页选中文本等）：单章，不回写进度。 */
    private static final class TextSource implements Source {
        private final String text;

        TextSource(String text) { this.text = text; }

        @Override public int chapters() { return 1; }
        @Override public String title() { return "朗读选中文本"; }
        @Override public String chapterLabel(int chapter) { return title(); }
        @Override public List<SpeechText.Passage> load(int chapter) {
            List<SpeechText.Block> blocks = Collections.singletonList(new SpeechText.Block(0, text, false));
            return SpeechText.sentences(SpeechText.spokenBlocks(blocks));
        }
        @Override public LazyChapterBook book() { return null; }
        @Override public ProgressModel progress() { return null; }
        @Override public void close() { }
    }
}
