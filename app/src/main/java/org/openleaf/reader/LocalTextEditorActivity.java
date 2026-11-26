package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.openleaf.reader.core.ContentContracts;
import org.openleaf.reader.core.TextEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, local text editor. Only acts on the document URIs the user explicitly selects. */
public final class LocalTextEditorActivity extends Activity {
    private static final int SAVE_COPY = 63;
    private static final int MAX_BUNDLE_DRAFT_CHARS = 32 * 1024;
    // 类型化编辑回传给阅读页：covered / saved_copy / conflict
    static final String EXTRA_EDIT_KIND = "editKind";
    static final String EXTRA_EDIT_URI = "editUri";
    static final String EXTRA_EDIT_SHA256 = "editSha256";
    static final String EXTRA_EDIT_SIZE = "editSize";
    static final String EXTRA_EDIT_MESSAGE = "editMessage";
    static final String EXTRA_SOURCE_OFFSET = "editSourceOffset";
    static final String EXTRA_ENCODING = "editEncoding";
    static final String EXTRA_EXPECTED_SHA256 = "editExpectedSha256";
    static final String EXTRA_RESUME_SOURCE_OFFSET = "editResumeSourceOffset";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri source;
    private String title;
    private LocalTextDocument document;
    private byte[] originalHash;
    private String baseline = "";
    private String draft;
    private EditText editor;
    private TextView status;
    private Button copy, overwrite, replace, clean, undo;
    private String undoText;
    private int undoCursor;
    private int savedCursor = -1;
    private long sourceOffset = -1;
    private TextEncoding.Mode encoding = TextEncoding.Mode.AUTO;
    private String expectedSha;
    private boolean sameReadingSource = true;
    private boolean saving;
    private SaveSession saveSession;

    /** Preserve only the in-flight operation through configuration recreation, not in the bundle. */
    private static final class SaveSession {
        final LocalTextDocument document;
        final String text, baseline;
        final byte[] originalHash;
        final boolean sameReadingSource;
        LocalTextEditorActivity owner;
        SaveResult result;
        SaveSession(LocalTextEditorActivity activity, String text) {
            owner = activity; document = activity.document; this.text = text;
            baseline = activity.baseline; originalHash = activity.originalHash;
            sameReadingSource = activity.sameReadingSource;
        }
        void deliver() { if (owner != null && result != null && owner.alive()) owner.finishSave(this, result); }
    }

    static final String EXTRA_READING_SOURCE = "editReadingSource";
    private static final class DraftState {
        final String text, undoText;
        final int undoCursor;
        DraftState(String text, String undoText, int undoCursor) {
            this.text = text; this.undoText = undoText; this.undoCursor = undoCursor;
        }
    }

    private static final class SaveResult {
        String notice, kind, uri, sha, pendingId;
        long size, resumeOffset = -1;
        byte[] hash;
        boolean sourceUnsafe;
    }
    private boolean busy, sourceChanged, fromReader;
    private Uri pendingCopy;
    private PendingWriteStore pendingWrites;
    static final String EXTRA_EDIT_PENDING_ID = "editPendingId";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        source = getIntent().getData();
        title = getIntent().getStringExtra("title");
        fromReader = getIntent().getBooleanExtra("fromReader", false);
        sourceOffset = getIntent().getLongExtra(EXTRA_SOURCE_OFFSET, -1);
        expectedSha = getIntent().getStringExtra(EXTRA_EXPECTED_SHA256);
        String encodedMode = getIntent().getStringExtra(EXTRA_ENCODING);
        if (encodedMode != null) {
            try { encoding = TextEncoding.Mode.valueOf(encodedMode); }
            catch (IllegalArgumentException ignored) { }
        }
        pendingWrites = new PendingWriteStore(this);
        if (source == null || !"content".equals(source.getScheme()) || !supported(title)) {
            Toast.makeText(this, "请选择 TXT 或 Markdown 文件", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        if (saved != null) {
            draft = saved.getString("draft");
            originalHash = saved.getByteArray("sourceHash");
            savedCursor = saved.getInt("cursor", -1);
        }
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.button(this, "返回", this::onBackPressed));
        TextView heading = Ui.title(this, title, 17);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(heading, Ui.weight());
        root.addView(bar);
        LinearLayout tools=Ui.row(this);
        replace=Ui.button(this,"查找替换",this::showReplace);
        clean=Ui.button(this,"清理乱码",this::confirmClean);
        undo = Ui.button(this, "撤销批量操作", this::undoBulk);
        tools.addView(replace, Ui.weight()); tools.addView(clean, Ui.weight()); tools.addView(undo, Ui.weight());
        root.addView(tools);
        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(false);
        editor.setSaveEnabled(false);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LocalTextDocument.MAX_BYTES)});
        editor.setContentDescription("文件编辑内容");
        editor.setTextSize(16);
        Ui.pad(editor, 12);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        status = Ui.text(this, "正在读取文本…", 13, Ui.MUTED);
        Ui.pad(status, 8);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        LinearLayout actions = Ui.row(this);
        copy = Ui.button(this, "另存副本", this::prepareCopy);
        overwrite = Ui.button(this, fromReader ? "保存并返回阅读" : "覆盖原文件", this::confirmOverwrite);
        actions.addView(copy, Ui.weight()); actions.addView(overwrite, Ui.weight());
        root.addView(actions);
        root.setFocusableInTouchMode(true); root.requestFocus();
        updateControls(true);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof SaveSession) {
            saveSession = (SaveSession) retained;
            saveSession.owner = this;
            document = saveSession.document;
            originalHash = saveSession.originalHash;
            baseline = saveSession.baseline;
            sameReadingSource = saveSession.sameReadingSource;
            editor.setText(saveSession.text);
            placeCursor(savedCursor);
            saving = true;
            status.setText("正在保存…");
            saveSession.deliver();
            return;
        }
        if (retained instanceof DraftState) {
            DraftState remembered = (DraftState) retained;
            draft = remembered.text;
            undoText = remembered.undoText;
            undoCursor = remembered.undoCursor;
        }
        worker.execute(() -> {
            try {
                LocalTextDocument loaded = LocalTextDocument.decode(readSource(), encoding);
                boolean readingVersion = !fromReader || getIntent().getBooleanExtra(EXTRA_READING_SOURCE, false)
                        && validateReadingSource(loaded.fingerprint);
                runOnUiThread(() -> {
                    if (!alive()) return;
                    document = loaded;
                    sourceChanged = originalHash != null && !Arrays.equals(originalHash, loaded.fingerprint);
                    if (originalHash == null) originalHash = loaded.fingerprint;
                    baseline = loaded.text;
                    sameReadingSource = readingVersion;
                    editor.setText(draft == null ? baseline : draft);
                    long initialCursor = savedCursor >= 0 ? savedCursor : sameReadingSource ? sourceOffset : 0;
                    placeCursor(initialCursor);
                    draft = null;
                    status.setText(saved != null && saved.getBoolean("draftNotInBundle") && !(retained instanceof DraftState)
                            ? "进程重启，未保存的大草稿未能恢复。" : sourceChanged ? "原文件已变化，草稿只可另存副本"
                            : !sameReadingSource ? "所选文件与当前阅读版本不同，只可另存副本。\n" + loaded.description()
                            : !canWriteSource() ? "没有原文件写入权限，可以另存副本。\n" + loaded.description()
                            : loaded.description());
                    updateControls(false);
                    if (pendingCopy != null) { Uri target = pendingCopy; pendingCopy = null; save(target, false); }
                });
            } catch (Exception failed) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    status.setText(getString(R.string.local_text_editor_error, message(failed)));
                    updateControls(false);
                });
            }
        });
    }

    private boolean validateReadingSource(byte[] loadedHash) {
        if (expectedSha != null && !expectedSha.isEmpty()
                && !expectedSha.equalsIgnoreCase(ContentContracts.toHex(loadedHash))) return false;
        String originalBookId = getIntent().getStringExtra("bookId");
        if (originalBookId == null) return false;
        try {
            BookStore store = new BookStore(this);
            BookStore.Book record = store.find(originalBookId);
            if (record == null || !source.equals(store.sourceUri(record))) return false;
            try (BookAccess access = store.open(record); InputStream in = new java.io.FileInputStream(access.file)) {
                return Arrays.equals(loadedHash, LocalTextDocument.hash(LocalTextDocument.readBytes(in)));
            }
        } catch (Exception unavailable) { return false; }
    }

    private boolean canWriteSource() {
        return checkUriPermission(source, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateControls(boolean working) {
        busy = working;
        boolean ready = !working && document != null;
        editor.setEnabled(ready); copy.setEnabled(ready); replace.setEnabled(ready); clean.setEnabled(ready);
        undo.setEnabled(ready && undoText != null);
        overwrite.setEnabled(ready && sameReadingSource && !sourceChanged && canWriteSource());
    }

    private void showReplace() {
        if (busy || document == null) return;
        EditText find = new EditText(this), replacement = new EditText(this);
        find.setHint("要查找的文字（支持姓名、错字等）");
        replacement.setHint("替换为（可留空表示删除）");
        CheckBox ignoreCase = new CheckBox(this);
        ignoreCase.setText("忽略大小写");
        ignoreCase.setChecked(true);
        LinearLayout form = Ui.column(this);
        Ui.pad(form, 16);
        form.addView(find); form.addView(replacement); form.addView(ignoreCase);
        new AlertDialog.Builder(this).setTitle("全文替换").setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("全部替换", (d, w) -> {
                    String needle = find.getText().toString();
                    if (needle.isEmpty()) { status.setText("请输入要查找的文字"); return; }
                    String sourceText = editor.getText().toString();
                    String flags = ignoreCase.isChecked() ? "(?i)" : "";
                    Pattern pattern = Pattern.compile(flags + Pattern.quote(needle));
                    Matcher matcher = pattern.matcher(sourceText);
                    int count = 0;
                    while (matcher.find()) count++;
                    if (count == 0) { status.setText("没有找到匹配的文字"); return; }
                    String replacementText = replacement.getText().toString();
                    long projected = sourceText.length() + (long) count * (replacementText.length() - needle.length());
                    if (projected > LocalTextDocument.MAX_BYTES) {
                        status.setText("替换后的文本超过编辑上限，草稿未改变"); return;
                    }
                    String result = matcher.replaceAll(Matcher.quoteReplacement(replacementText));
                    applyBulk(result, "已替换 " + count + " 处");
                }).show();
    }

    private void confirmClean() {
        if (busy || document == null) return;
        new AlertDialog.Builder(this).setTitle("清理乱码")
                .setMessage("将删除文本中的替换字符（U+FFFD）、孤立控制字符和开头 BOM，保留正常换行与制表。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (d, w) -> {
                    String sourceText = editor.getText().toString();
                    StringBuilder cleaned = new StringBuilder(sourceText.length());
                    for (int i = 0; i < sourceText.length(); ) {
                        int cp = sourceText.codePointAt(i);
                        int width = Character.charCount(cp);
                        if (!(cp == 0xFFFD || (cp < 32 && cp != '\n' && cp != '\r' && cp != '\t')
                                || (i == 0 && cp == 0xFEFF)))
                            cleaned.appendCodePoint(cp);
                        i += width;
                    }
                    String result = cleaned.toString().replace("ï¿½", "");
                    applyBulk(result, "已清理异常字符");
                }).show();
    }

    private void prepareCopy() {
        if (busy || document == null) return;
        String snapshot = editor.getText().toString();
        LocalTextDocument local = document;
        updateControls(true);
        worker.execute(() -> {
            String error = null;
            try { local.encode(snapshot); }
            catch (IOException failure) { error = message(failure); }
            String validation = error;
            runOnUiThread(() -> {
                if (!alive()) return;
                updateControls(false);
                if (validation == null) chooseCopy();
                else status.setText(validation);
            });
        });
    }

    private void placeCursor(long desired) {
        int offset = LocalTextDocument.cursorAt(editor.getText().toString(), desired);
        editor.setSelection(offset);
        editor.post(() -> {
            if (!alive() || editor.getSelectionStart() != offset || editor.getLayout() == null) return;
            int line = editor.getLayout().getLineForOffset(offset);
            int top = editor.getLayout().getLineTop(line);
            editor.scrollTo(0, Math.max(0, top - editor.getHeight() / 3));
        });
    }

    private void applyBulk(String result, String label) {
        if (result.contentEquals(editor.getText())) { status.setText("没有需要修改的文字"); return; }
        undoText = editor.getText().toString();
        undoCursor = Math.max(0, editor.getSelectionStart());
        editor.setText(result);
        placeCursor(LocalTextDocument.remapOffset(undoText, result, undoCursor));
        status.setText(label + "；原文件尚未改变");
        updateControls(false);
    }

    private void undoBulk() {
        if (busy || undoText == null) return;
        new AlertDialog.Builder(this).setTitle("撤销上一次批量操作？")
                .setMessage("恢复到上次批量操作前的草稿；之后键入的修改也会被撤销。")
                .setNegativeButton("取消", null).setPositiveButton("撤销", (d, w) -> {
                    editor.setText(undoText);
                    placeCursor(undoCursor);
                    undoText = null;
                    status.setText("已恢复批量操作前的草稿");
                    updateControls(false);
                }).show();
    }

    private void chooseCopy() {
        if (busy || document == null) return;
        int dot = title.lastIndexOf('.');
        String name = title.substring(0, dot) + "-副本" + title.substring(dot);
        try {
            Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(pick, SAVE_COPY);
        } catch (android.content.ActivityNotFoundException unavailable) { status.setText("系统没有可用的文件选择器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_COPY || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri target = data.getData();
        if (!"content".equals(target.getScheme()) || sameDocument(source, target)) {
            status.setText("请选择与原文件不同的保存位置"); return;
        }
        if (document == null) pendingCopy = target;
        else save(target, false);
    }

    private void confirmOverwrite() {
        if (busy || document == null || !sameReadingSource || sourceChanged || !canWriteSource()) return;
        if (!dirty()) { status.setText("没有需要保存的修改"); return; }
        new AlertDialog.Builder(this).setTitle("确定覆盖原文件？")
                .setMessage((fromReader ? "保存会覆盖本书的原文件并重新打开阅读位置。" : "保存会覆盖所选的本地文件。") + "系统文件提供器不保证写入可撤销；重要文本请先另存副本。" + (document.mixedNewlines ? " 混合换行将统一为 LF。" : ""))
                .setNegativeButton("取消", null).setPositiveButton("覆盖原文件", (dialog, which) -> save(source, true)).show();
    }

    private void save(Uri target, boolean replaceOriginal) {
        if (busy || document == null || !alive()) return;
        final SaveSession session = new SaveSession(this, editor.getText().toString());
        final android.content.Context app = getApplicationContext();
        final Uri sourceUri = source;
        final long anchor = sourceOffset;
        final String bookId = getIntent().getStringExtra("bookId");
        final int callingPid = Process.myPid(), callingUid = Process.myUid();
        saveSession = session;
        saving = true;
        updateControls(true);
        status.setText("正在保存…");
        worker.execute(() -> {
            SaveResult result = new SaveResult();
            boolean writeAttempted = false;
            boolean conflict = false;
            try {
                if (replaceOriginal && session.text.isEmpty()) throw new IOException("原文件不能为空，请保留正文或另存副本");
                byte[] bytes = session.document.encode(session.text);
                byte[] intended = LocalTextDocument.hash(bytes);
                if (replaceOriginal) {
                    if (app.checkUriPermission(sourceUri, callingPid, callingUid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            != PackageManager.PERMISSION_GRANTED) throw new IOException("没有原文件写入授权，请另存副本");
                    try (InputStream in = app.getContentResolver().openInputStream(sourceUri)) {
                        if (!Arrays.equals(session.originalHash, LocalTextDocument.hash(LocalTextDocument.readBytes(in)))) {
                            conflict = true;
                            throw new IOException("原文件已被外部修改，未覆盖；草稿可另存副本");
                        }
                    }
                }
                PendingWriteStore journal = new PendingWriteStore(app);
                result.pendingId = journal.prepare(target.toString(), sourceUri.toString(), session.originalHash,
                        intended, bytes.length, replaceOriginal, bookId);
                writeAttempted = true;
                try (OutputStream out = app.getContentResolver().openOutputStream(target, "rwt")) {
                    if (out == null) throw new IOException("无法打开保存目标");
                    out.write(bytes);
                    out.flush();
                }
                if (replaceOriginal) {
                    try (InputStream in = app.getContentResolver().openInputStream(target)) {
                        if (!Arrays.equals(intended, LocalTextDocument.hash(LocalTextDocument.readBytes(in))))
                            throw new IOException("提供器保存后的内容不一致");
                    }
                    journal.updateState(result.pendingId, PendingWriteStore.COMMITTED, null);
                    result.hash = intended;
                    result.kind = ContentContracts.EditKind.COVERED.name();
                    result.sha = ContentContracts.toHex(intended);
                    if (anchor >= 0) result.resumeOffset = LocalTextDocument.remapOffset(
                            session.document.text, session.text.replace("\r\n", "\n").replace('\r', '\n'), anchor);
                    result.notice = "原文件已保存并校验";
                } else {
                    journal.acknowledge(result.pendingId);
                    result.kind = ContentContracts.EditKind.SAVED_COPY.name();
                    result.notice = "已另存副本；原文件未改变";
                }
                result.uri = target.toString();
                result.size = bytes.length;
            } catch (Exception failure) {
                result.sourceUnsafe = replaceOriginal && (conflict || writeAttempted);
                result.notice = app.getString(R.string.local_text_editor_error, message(failure));
                if (replaceOriginal && writeAttempted) result.notice += "。无法确认原件完整性；请先另存草稿，再检查原件。";
                if (result.pendingId != null) {
                    try { new PendingWriteStore(app).updateState(result.pendingId,
                            conflict ? PendingWriteStore.CONFLICT : PendingWriteStore.FAILED, result.notice); }
                    catch (Exception ignored) { }
                }
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                session.result = result;
                session.deliver();
            });
        });
    }

    private void finishSave(SaveSession session, SaveResult result) {
        if (saveSession != session) return;
        saving = false;
        saveSession = null;
        session.owner = null;
        sourceChanged |= result.sourceUnsafe;
        if (result.hash != null) originalHash = result.hash;
        if (result.kind != null) baseline = session.text;
        status.setText(result.notice);
        updateControls(false);
        if (fromReader && result.kind != null) {
            Intent out = new Intent().putExtra(EXTRA_EDIT_KIND, result.kind)
                    .putExtra(EXTRA_EDIT_URI, result.uri).putExtra(EXTRA_EDIT_SHA256, result.sha)
                    .putExtra(EXTRA_EDIT_SIZE, result.size).putExtra(EXTRA_EDIT_MESSAGE, result.notice)
                    .putExtra(EXTRA_EDIT_PENDING_ID, result.pendingId)
                    .putExtra(EXTRA_RESUME_SOURCE_OFFSET, result.resumeOffset);
            setResult(RESULT_OK, out);
            finish();
        }
    }

    private byte[] readSource() throws IOException {
        try (InputStream in = getContentResolver().openInputStream(source)) { return LocalTextDocument.readBytes(in); }
    }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private boolean dirty() { return document != null && !baseline.contentEquals(editor.getText()); }
    private static String message(Exception error) { return error.getMessage() == null ? "请重新授权或尝试另存副本" : error.getMessage(); }
    private static boolean supported(String title) {
        if (title == null) return false;
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }
    private boolean sameDocument(Uri left, Uri right) {
        if (left.equals(right)) return true;
        if (left.getAuthority() == null || !left.getAuthority().equals(right.getAuthority())) return false;
        try { return DocumentsContract.getDocumentId(left).equals(DocumentsContract.getDocumentId(right)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    @Override public void onBackPressed() {
        if (saving) { Toast.makeText(this, "请等待保存完成", Toast.LENGTH_SHORT).show(); return; }
        if (!dirty()) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle("文本尚未保存")
                .setPositiveButton("继续编辑", null).setNegativeButton("放弃修改", (dialog, which) -> finish()).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (originalHash != null) state.putByteArray("sourceHash", originalHash);
        if (editor != null) state.putInt("cursor", editor.getSelectionStart());
        String unsaved = dirty() ? editor.getText().toString() : draft;
        if (unsaved != null && unsaved.length() <= MAX_BUNDLE_DRAFT_CHARS) state.putString("draft", unsaved);
        else if (unsaved != null) state.putBoolean("draftNotInBundle", true);
        super.onSaveInstanceState(state);
    }

    @Override public Object onRetainNonConfigurationInstance() {
        if (saveSession != null) return saveSession;
        String unsaved = dirty() ? editor.getText().toString() : draft;
        return unsaved != null || undoText != null ? new DraftState(unsaved, undoText, undoCursor) : null;
    }

    @Override protected void onDestroy() {
        if (saveSession != null && saveSession.owner == this) saveSession.owner = null;
        if (saving) worker.shutdown(); else worker.shutdownNow();
        super.onDestroy();
    }
}
