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
    // 类型化编辑回传给阅读页：covered / saved_copy / conflict
    static final String EXTRA_EDIT_KIND = "editKind";
    static final String EXTRA_EDIT_URI = "editUri";
    static final String EXTRA_EDIT_SHA256 = "editSha256";
    static final String EXTRA_EDIT_SIZE = "editSize";
    static final String EXTRA_EDIT_MESSAGE = "editMessage";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri source;
    private String title;
    private LocalTextDocument document;
    private byte[] originalHash;
    private String baseline = "";
    private String draft;
    private EditText editor;
    private TextView status;
    private Button copy, overwrite, replace, clean;
    private boolean busy, sourceChanged, fromReader;
    private Uri pendingCopy;
    private PendingWriteStore pendingWrites;
    static final String EXTRA_EDIT_PENDING_ID = "editPendingId";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        source = getIntent().getData();
        title = getIntent().getStringExtra("title");
        fromReader = getIntent().getBooleanExtra("fromReader", false);
        pendingWrites = new PendingWriteStore(this);
        if (source == null || !"content".equals(source.getScheme()) || !supported(title)) {
            Toast.makeText(this, "请选择 TXT 或 Markdown 文件", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        if (saved != null) {
            draft = saved.getString("draft");
            originalHash = saved.getByteArray("sourceHash");
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
        tools.addView(replace,Ui.weight());tools.addView(clean,Ui.weight());
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
        copy = Ui.button(this, "另存副本", this::chooseCopy);
        overwrite = Ui.button(this, "覆盖原文件", this::confirmOverwrite);
        actions.addView(copy, Ui.weight()); actions.addView(overwrite, Ui.weight());
        root.addView(actions);
        root.setFocusableInTouchMode(true); root.requestFocus();
        updateControls(true);
        worker.execute(() -> {
            try {
                LocalTextDocument loaded = LocalTextDocument.decode(readSource());
                runOnUiThread(() -> {
                    if (!alive()) return;
                    document = loaded;
                    sourceChanged = originalHash != null && !Arrays.equals(originalHash, loaded.fingerprint);
                    if (originalHash == null) originalHash = loaded.fingerprint;
                    baseline = loaded.text;
                    editor.setText(draft == null ? baseline : draft);
                    draft = null;
                    status.setText(sourceChanged ? "原文件已变化，草稿只可另存副本" : loaded.description());
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

    private boolean canWriteSource() {
        return checkUriPermission(source, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateControls(boolean working) {
        busy = working;
        boolean ready = !working && document != null;
        editor.setEnabled(ready); copy.setEnabled(ready); replace.setEnabled(ready); clean.setEnabled(ready);
        overwrite.setEnabled(ready && !sourceChanged && canWriteSource());
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
                    String result = matcher.replaceAll(Matcher.quoteReplacement(replacement.getText().toString()));
                    editor.setText(result);
                    status.setText("已替换 " + count + " 处；选择“覆盖原文件”后生效");
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
                    int removed = 0;
                    for (int i = 0; i < sourceText.length(); ) {
                        int cp = sourceText.codePointAt(i);
                        int width = Character.charCount(cp);
                        if (cp == 0xFFFD || (cp < 32 && cp != '\n' && cp != '\r' && cp != '\t')
                                || (i == 0 && cp == 0xFEFF)) removed++;
                        else cleaned.appendCodePoint(cp);
                        i += width;
                    }
                    String result = cleaned.toString().replace("ï¿½", "");
                    editor.setText(result);
                    status.setText(removed == 0 && result.length() == sourceText.length()
                            ? "没有发现明显乱码字符" : "已清理 " + removed + " 个异常字符；保存后生效");
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
        if (busy || document == null || sourceChanged || !canWriteSource()) return;
        if (!dirty()) { status.setText("没有需要保存的修改"); return; }
        new AlertDialog.Builder(this).setTitle("确定覆盖原文件？")
                .setMessage("这将写入所选本地文件，不会同步到书架中已导入的副本。系统文件提供器不保证写入可撤销，建议先另存副本。")
                .setNegativeButton("取消", null).setPositiveButton("覆盖原文件", (dialog, which) -> save(source, true)).show();
    }

    private void save(Uri target, boolean replaceOriginal) {
        if (busy || document == null || !alive()) return;
        final String snapshot = editor.getText().toString();
        final LocalTextDocument local = document;
        final byte[] initialHash = originalHash;
        updateControls(true);
        status.setText("正在保存…");
        final String[] pendingId = {null};
        worker.execute(() -> {
            boolean conflict = false;
            String result;
            byte[] savedHash = null;
            String outcomeKind = null, outcomeSha = null, outcomeUri = null, outcomeMessage = null;
            long outcomeSize = 0;
            try {
                byte[] bytes = local.encode(snapshot);
                if (replaceOriginal) {
                    if (!canWriteSource()) throw new IOException("没有原文件写入授权，请另存副本");
                    if (!Arrays.equals(initialHash, LocalTextDocument.hash(readSource()))) {
                        conflict = true;
                        throw new IOException("原文件已被外部修改，未覆盖；可另存副本");
                    }
                }
                // 写前登记意图：进程死在写入与版本登记之间时可收敛
                byte[] intended = LocalTextDocument.hash(bytes);
                String bookId = getIntent().getStringExtra("bookId");
                pendingId[0] = pendingWrites.prepare(target.toString(),
                        source == null ? title : source.toString(), initialHash, intended,
                        bytes.length, replaceOriginal, bookId);
                // rwt requests truncation so a shorter edit cannot leave stale trailing bytes.
                try (OutputStream out = getContentResolver().openOutputStream(target, "rwt")) {
                    if (out == null) throw new IOException("无法打开保存目标");
                    out.write(bytes);
                    out.flush();
                }
                if (replaceOriginal) {
                    savedHash = intended;
                    pendingWrites.updateState(pendingId[0], PendingWriteStore.COMMITTED, null);
                    outcomeKind = ContentContracts.EditKind.COVERED.name();
                    outcomeSha = ContentContracts.toHex(savedHash);
                    outcomeUri = target.toString();
                    outcomeSize = bytes.length;
                    outcomeMessage = "原文件已保存";
                } else {
                    pendingWrites.acknowledge(pendingId[0]);
                    outcomeKind = ContentContracts.EditKind.SAVED_COPY.name();
                    outcomeUri = target.toString();
                    outcomeSize = bytes.length;
                    outcomeMessage = "已另存副本";
                }
                result = replaceOriginal ? "原文件已保存；书架副本未改变" : "已另存副本；原文件未改变";
            } catch (Exception failure) {
                result = getString(R.string.local_text_editor_error, message(failure));
                if (pendingId[0] != null)
                    pendingWrites.updateState(pendingId[0], conflict ? PendingWriteStore.CONFLICT : PendingWriteStore.FAILED, result);
                if (conflict) {
                    outcomeKind = ContentContracts.EditKind.CONFLICT.name();
                    outcomeMessage = result;
                }
            }
            final String notice = result;
            final boolean stale = conflict;
            final byte[] newHash = savedHash;
            final String pendingFinal = pendingId[0];
            final String kindFinal = outcomeKind, shaFinal = outcomeSha, uriFinal = outcomeUri,
                    messageFinal = outcomeMessage;
            final long sizeFinal = outcomeSize;
            runOnUiThread(() -> {
                if (!alive()) return;
                sourceChanged |= stale;
                if (newHash != null) originalHash = newHash;
                if (newHash != null || kindFinal != null && ContentContracts.EditKind.SAVED_COPY.name().equals(kindFinal))
                    baseline = snapshot;
                status.setText(notice);
                updateControls(false);
                if (fromReader && kindFinal != null) {
                    Intent out = new Intent();
                    out.putExtra(EXTRA_EDIT_KIND, kindFinal);
                    out.putExtra(EXTRA_EDIT_URI, uriFinal);
                    out.putExtra(EXTRA_EDIT_SHA256, shaFinal);
                    out.putExtra(EXTRA_EDIT_SIZE, sizeFinal);
                    out.putExtra(EXTRA_EDIT_MESSAGE, messageFinal);
                    out.putExtra(EXTRA_EDIT_PENDING_ID, pendingFinal);
                    setResult(RESULT_OK, out);
                    finish();
                }
            });
        });
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
        if (busy) { Toast.makeText(this, "请等待文件操作完成", Toast.LENGTH_SHORT).show(); return; }
        if (!dirty()) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle("文本尚未保存")
                .setPositiveButton("继续编辑", null).setNegativeButton("放弃修改", (dialog, which) -> finish()).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (originalHash != null) state.putByteArray("sourceHash", originalHash);
        if (dirty()) state.putString("draft", editor.getText().toString());
        else if (draft != null) state.putString("draft", draft);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
