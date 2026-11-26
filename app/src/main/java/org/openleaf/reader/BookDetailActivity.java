package org.openleaf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.CoverInfer;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All form edits are drafts until one transactional Save, including tags and cover selection. */
public final class BookDetailActivity extends Activity {
    private static final int PICK_COVER=64,EXPORT_BOOK=62,RELINK_BOOK=65;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<String> draftTags=new ArrayList<>();
    private final ArrayList<String> folderIds=new ArrayList<>();
    private BookStore store;
    private BookMetadataStore metadata;
    private BookMetadataStore.Info original;
    private String bookId,coverName="",baseline,exportSharer="";
    private boolean busy,ready,suppressDraft;
    private EditText title,author,description,coverText,tagInput,review;
    private ImageView cover;
    private TextView coverPlaceholder,status,statistics;
    private CheckBox favorite;
    private Spinner category;
    private TextView sourceInfo,reviewCount;
    /** 标签区由纯文本改为可点 pill 容器：点标签回流到书库按该标签筛选。 */
    private LinearLayout tags;
    private Button save,chooseCover,removeCover,addTag,editTags,export,read,relink;
    private ScrollView scroll;
    private AlertDialog dialog;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        bookId=getIntent().getStringExtra("bookId");
        if(bookId==null||bookId.isEmpty()){finish();return;}
        store=new BookStore(getApplicationContext());metadata=new BookMetadataStore(getApplicationContext());
        build();setBusy(true);
        worker.execute(()->{
            try{
                BookMetadataStore.Info info=metadata.get(bookId);
                String discoveredCover=info.coverName;
                if(discoveredCover.isEmpty()&&"epub".equals(info.book.format)){
                    try{
                        try(BookAccess access=store.open(info.book)){
                            BookParser.CoverData cover=BookParser.discoverCover(access.file);
                            if(cover!=null){
                                String name=CoverFiles.importBytes(getApplicationContext(),cover.bytes);
                                metadata.setCover(bookId,name);discoveredCover=name;
                            }
                        }
                    }catch(Exception ignored){ /* Keep the text placeholder when extraction fails. */ }
                }
                final String coverForDraft=discoveredCover;
                // 数据侧：TXT/PDF 元数据推断，只产出 draft 候选；未命中静默留空，异常带短串状态
                final String[] inferred = inferDraft(info);
                List<BookStore.Folder> folders=store.allFolders();
                List<BookStore.Source> sources=store.sources(bookId);
                long measuredSize;
                try { measuredSize=store.size(info.book); } catch(Exception unavailable) { measuredSize=-1; }
                final long size=measuredSize;
                final boolean referenced=store.isReferenced(info.book);
                runOnUiThread(()->{
                    if(isDestroyed()||isFinishing())return;
                    original=new BookMetadataStore.Info(info.book,info.author,info.description,info.coverText,
                            coverForDraft,info.favorite,info.tags,info.folderIds,info.review);
                    suppressDraft=true;
                    title.setText(info.book.title);author.setText(info.author);description.setText(info.description);review.setText(info.review);
                    coverText.setText(info.coverText);coverName=coverForDraft;favorite.setChecked(info.favorite);
                    draftTags.clear();draftTags.addAll(info.tags);bindFolders(folders,info.folderIds);
                    ready=true;baseline=snapshot();
                    if(saved!=null)restoreDraft(saved);
                    // 推断候选只填 draft（Q9）；不写库、不覆盖用户已有值或旋转恢复值
                    if(inferred[0]!=null&&author.getText().toString().trim().isEmpty())author.setText(inferred[0]);
                    if(inferred[1]!=null&&coverText.getText().toString().trim().isEmpty())coverText.setText(inferred[1]);
                    suppressDraft=false;showTags();refreshCover();
                    StringBuilder fileDetails=new StringBuilder("格式：").append(info.book.format.toUpperCase(Locale.ROOT))
                            .append("  ·  大小：").append(size<0?"未知或来源暂不可读":android.text.format.Formatter.formatFileSize(this,size))
                            .append("\n导入时间：").append(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(info.book.added)));
                    if(sources.isEmpty())fileDetails.append("\n原文件：旧书或书包导入，尚未登记来源");
                    else for(int i=0;i<Math.min(sources.size(),5);i++)fileDetails.append("\n原文件：").append(sourceLabel(sources.get(i)));
                    if(sources.size()>5)fileDetails.append("\n另有 ").append(sources.size()-5).append(" 个来源，可在删除管理页查看");
                    fileDetails.append(referenced?"\n存储方式：原文件引用，不在书库保留文件副本。移动、删除原文件或撤销授权后需重新关联。":"\n存储方式：旧应用副本或书包内容；原有副本继续可读。");
                    fileDetails.append("\n修改书籍信息不重命名或改写原文件。\n阅读方式：按文件格式自动选择。");
                    relink.setVisibility(referenced?View.VISIBLE:View.GONE);
                    sourceInfo.setText(fileDetails);
                    refreshStatistics();status.setText("");setBusy(false);
                    // 三态：命中候选只提示「填草稿待确认」，不改写入逻辑与 baseline/撤销机制
                    if(inferred[2]!=null)status.setText(inferred[2]);
                    else if(inferred[0]!=null||inferred[1]!=null)status.setText("已根据文件名/文件元数据填入草稿，请确认后保存");
                    if(saved!=null)scroll.post(()->scroll.scrollTo(0,saved.getInt("scrollY")));
                });
            }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){status.setText("读取失败："+message(error));setBusy(false);}});}
        });
    }
    /**
     * TXT/PDF 元数据推断（设计 §8.2）：返回 {author 候选, coverText 候选, 异常短串}。
     * 未命中静默留空；不写库，调用方只把候选填进 draft。
     */
    private String[] inferDraft(BookMetadataStore.Info info) {
        String[] none={null,null,null};
        String format=info.book.format==null?"":info.book.format.toLowerCase(Locale.ROOT);
        if(!"txt".equals(format)&&!"pdf".equals(format))return none;
        try(BookAccess access=store.open(info.book)){
            File file=access.file;
            String author=null;
            if(info.author==null||info.author.isEmpty()){
                CoverInfer.Meta meta=CoverInfer.fromFileName(file.getName());
                author=meta==null?null:meta.author;
            }
            String caption=null;
            if(info.coverText==null||info.coverText.isEmpty()){
                if("pdf".equals(format)){
                    byte[] headTail=readPdfHeadTail(file);
                    caption=CoverInfer.pdfInfoValue(headTail,"/Title");
                    if(caption==null)caption=CoverInfer.pdfInfoValue(headTail,"/Author");
                }else caption=CoverInfer.fromFirstLine(firstTextOf(file));
            }
            return new String[]{author,caption,null};
        }catch(Exception error){
            return new String[]{null,null,"元数据自动提取失败："+shortReason(error)};
        }
    }

    /** TXT 只读前 128 KiB（大书不整本解码），沿用 LocalTextDocument 的编码识别。 */
    private static String firstTextOf(File file)throws IOException{
        byte[] buffer=new byte[128*1024];
        int count=0;
        try(FileInputStream in=new FileInputStream(file)){
            while(count<buffer.length){
                int n=in.read(buffer,count,buffer.length-count);
                if(n<0)break;
                count+=n;
            }
        }
        return LocalTextDocument.decode(java.util.Arrays.copyOf(buffer,count)).text;
    }

    /** PDF 首尾各 256 KiB：字典常位于文件尾，避免整本读入。 */
    private static byte[] readPdfHeadTail(File file)throws IOException{
        long size=file.length();
        int head=(int)Math.min(size,256L*1024);
        int tail=(int)Math.min(Math.max(0,size-head),256L*1024);
        byte[] out=new byte[head+tail];
        try(java.io.RandomAccessFile raf=new java.io.RandomAccessFile(file,"r")){
            raf.readFully(out,0,head);
            if(tail>0){raf.seek(size-tail);raf.readFully(out,head,tail);}
        }
        return out;
    }

    private static String shortReason(Exception error){
        if(error instanceof SecurityException)return "文件访问授权已失效";
        return "未知错误（"+error.getClass().getSimpleName()+"）";
    }

    private static final int PANEL = 0xFFFAFAF9, COVER_PANEL = 0xFFF2F1EE;

    private void build() {
        LinearLayout root = Ui.column(this);
        Ui.mount(this, root);
        LinearLayout header = Ui.row(this);
        header.setBackgroundColor(Color.WHITE);
        header.setPadding(dp(18), dp(15), dp(18), dp(15));
        TextView heading = Ui.title(this, "编辑书籍信息", 21);
        heading.setTextColor(Ui.INK);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, Ui.weight());
        root.addView(header);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = Ui.column(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout hero = Ui.column(this);
        hero.setBackgroundColor(COVER_PANEL);
        Ui.pad(hero, 16);
        body.addView(hero);
        LinearLayout coverRow = Ui.row(this);
        coverRow.setGravity(Gravity.TOP);
        hero.addView(coverRow);

        FrameLayout coverFrame = new FrameLayout(this);
        coverFrame.setBackground(Ui.shape(0xFFE7E5E4, 8, this));
        coverFrame.setClipToOutline(true);
        int coverWidth = Math.min(112, Math.max(76, getResources().getConfiguration().screenWidthDp / 3));
        coverRow.addView(coverFrame, new LinearLayout.LayoutParams(dp(coverWidth), dp(coverWidth * 3 / 2)));
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setContentDescription("书籍封面");
        coverFrame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        coverPlaceholder = Ui.text(this, "无封面", 16, Ui.GREEN);
        coverPlaceholder.setGravity(Gravity.CENTER);
        coverPlaceholder.setMaxLines(5);
        coverPlaceholder.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(coverPlaceholder, 8);
        coverFrame.addView(coverPlaceholder, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout fields = Ui.column(this);
        LinearLayout.LayoutParams fieldsParams = Ui.weight();
        fieldsParams.setMarginStart(dp(14));
        coverRow.addView(fields, fieldsParams);
        title = field(fields, "书名", "必填", 3, 512, 20);
        author = field(fields, "作者", "选填", 1, 256, 16);
        coverText = field(hero, "封面文字", "选填，显示在默认封面上", 1, 160, 15);
        TextWatcher preview = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!suppressDraft) refreshPlaceholder();
            }
        };
        coverText.addTextChangedListener(preview);
        title.addTextChangedListener(preview);
        chooseCover = Ui.button(this, "选择封面…", this::pickCover);
        removeCover = Ui.button(this, "移除封面", () -> {
            discardDraftCover();
            coverName = "";
            refreshCover();
        });
        hero.addView(actionRow(chooseCover, removeCover));

        LinearLayout form = Ui.column(this);
        Ui.pad(form, 16);
        form.setBackgroundColor(PANEL);
        body.addView(form);
        description = field(form, "简介", "选填", 5, 16000, 16);
        review = field(form, "我的书评", "写下 200–300 字的阅读感受；留空可清除", 8, 2000, 16);
        review.setMinLines(4);
        reviewCount = Ui.text(this, "0 / 300 字 · 选填，填写时需 200–300 字", 12, Ui.MUTED);
        form.addView(reviewCount);
        review.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {
                String value = s.toString().trim();
                int count = value.codePointCount(0, value.length());
                reviewCount.setText(count + " / 300 字 · 选填，填写时需 200–300 字");
                reviewCount.setTextColor(count > 300 || count > 0 && count < 200 ? 0xFFAD493D : Ui.MUTED);
            }
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });
        form.addView(Ui.text(this, "书评会与标签一起随书包导入、导出。", 12, Ui.MUTED));
        Ui.gap(form, 10);
        form.addView(Ui.text(this, "标签", 13, Ui.MUTED));
        LinearLayout selectedTags = Ui.row(this);
        tags = new LinearLayout(this);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        selectedTags.addView(tags, Ui.weight());
        editTags = Ui.button(this, "管理标签…", this::editTags);
        selectedTags.addView(editTags);
        form.addView(selectedTags);
        LinearLayout tagRow = Ui.row(this);
        tagInput = new EditText(this);
        tagInput.setSingleLine(true);
        tagInput.setHint("输入新标签");
        tagInput.setContentDescription("输入新标签");
        tagInput.setTextSize(15);
        tagInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        tagInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tagInput.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            addTag();
            return true;
        });
        tagRow.addView(tagInput, Ui.weight());
        addTag = Ui.button(this, "添加标签", this::addTag);
        tagRow.addView(addTag);
        form.addView(tagRow);
        favorite = new CheckBox(this);
        favorite.setText("加入珍藏");
        favorite.setTextColor(Ui.INK);
        favorite.setMinHeight(dp(48));
        form.addView(favorite);
        form.addView(Ui.text(this, "书架分类", 13, Ui.MUTED));
        category = new Spinner(this, Spinner.MODE_DIALOG);
        category.setContentDescription("书架分类");
        category.setPrompt("书架分类");
        category.setMinimumHeight(dp(48));
        form.addView(category, new LinearLayout.LayoutParams(-1, -2));
        form.addView(Ui.text(this, "选择新分类会替换这本书的全部归档位置。", 12, Ui.MUTED));

        LinearLayout info = Ui.column(this);
        Ui.pad(info, 16);
        body.addView(info);
        info.addView(Ui.title(this, "文件信息", 16));
        sourceInfo = Ui.text(this, "正在读取文件信息…", 13, Ui.MUTED);
        sourceInfo.setTextIsSelectable(true);
        Ui.gap(info, 4);
        info.addView(sourceInfo);
        relink=Ui.button(this,"重新关联原文件",this::chooseRelink);
        relink.setVisibility(View.GONE);
        info.addView(relink);
        Ui.gap(info, 14);
        info.addView(Ui.title(this, "阅读记录", 16));
        statistics = Ui.text(this, "", 14, Ui.MUTED);
        Ui.gap(info, 4);
        info.addView(statistics);
        Ui.gap(info, 8);
        read = Ui.button(this, "开始阅读", this::openBook);
        export = Ui.button(this, "导出标签与书评书包", this::chooseExport);
        info.addView(actionRow(read, export));
        Ui.gap(info, 8);
        LinearLayout listenRow = Ui.row(this);
        listenRow.addView(Ui.button(this, "听书", this::openTts), Ui.weight());
        info.addView(listenRow);

        View rule = new View(this);
        rule.setBackgroundColor(0xFFE7E5E4);
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout footer = Ui.column(this);
        Ui.pad(footer, 8);
        footer.setBackgroundColor(PANEL);
        status = Ui.text(this, "正在加载…", 13, Ui.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(status);
        status.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                status.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
        Button cancel = Ui.button(this, "取消", this::onBackPressed);
        save = Ui.button(this, "确定", this::saveDraft);
        save.setTextColor(Color.WHITE);
        save.setBackgroundTintList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xFF68766D, Ui.GREEN}));
        footer.addView(actionRow(cancel, save));
        root.addView(footer);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    private int dp(int size) { return Ui.dp(this, size); }

    private LinearLayout actionRow(Button first, Button second) {
        LinearLayout row = Ui.row(this);
        row.addView(first, Ui.weight());
        LinearLayout.LayoutParams secondParams = Ui.weight();
        secondParams.setMarginStart(dp(8));
        row.addView(second, secondParams);
        return row;
    }

    private EditText field(LinearLayout parent, String label, String hint, int maxLines, int limit, int size) {
        Ui.gap(parent, 4);
        TextView caption = Ui.text(this, label, 13, Ui.MUTED);
        parent.addView(caption);
        EditText value = new EditText(this);
        value.setId(View.generateViewId());
        caption.setLabelFor(value.getId());
        value.setTextSize(size);
        value.setHint(hint);
        value.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | (maxLines > 1 ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        value.setSingleLine(maxLines == 1);
        value.setMaxLines(maxLines);
        value.setMinLines(label.equals("简介") ? 2 : 1);
        value.setMinimumHeight(dp(48));
        value.setGravity(Gravity.TOP | Gravity.START);
        value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        parent.addView(value, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }
    private void bindFolders(List<BookStore.Folder> folders,List<String> existing){
        Map<String,BookStore.Folder> byId=new HashMap<>();for(BookStore.Folder f:folders)byId.put(f.id,f);
        ArrayList<String> names=new ArrayList<>();folderIds.clear();folderIds.add(null);folderIds.add(BookStore.UNCLASSIFIED);
        StringBuilder current=new StringBuilder();for(String id:existing){BookStore.Folder f=byId.get(id);if(f!=null){if(current.length()>0)current.append("、");current.append(f.name);}}
        names.add("保留现有分类"+(current.length()==0?"（未分类）":"（"+current+"）"));names.add("未分类（移出全部归档）");
        for(BookStore.Folder f:folders){
            ArrayList<String> parts=new ArrayList<>();Set<String> seen=new HashSet<>();BookStore.Folder at=f;
            while(at!=null&&seen.add(at.id)&&parts.size()<129){parts.add(0,at.name);at=byId.get(at.parentId);}
            names.add(TextUtils.join(" / ",parts));folderIds.add(f.id);
        }
        category.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
    }
    private String folderSelection(){int selected=category.getSelectedItemPosition();return selected>=0&&selected<folderIds.size()?folderIds.get(selected):null;}
    private String snapshot(){
        org.json.JSONArray value=new org.json.JSONArray();value.put(title.getText().toString());value.put(author.getText().toString());value.put(description.getText().toString());
        value.put(review.getText().toString());value.put(coverText.getText().toString());value.put(coverName);value.put(favorite.isChecked());value.put(folderSelection());value.put(new org.json.JSONArray(draftTags));
        return value.toString();
    }
    private boolean dirty(){return ready&&baseline!=null&&!baseline.equals(snapshot());}
    private void restoreDraft(Bundle state){
        title.setText(state.getString("title",title.getText().toString()));author.setText(state.getString("author",author.getText().toString()));
        description.setText(state.getString("description",description.getText().toString()));review.setText(state.getString("review",review.getText().toString()));coverText.setText(state.getString("coverText",coverText.getText().toString()));
        favorite.setChecked(state.getBoolean("favorite",favorite.isChecked()));coverName=state.getString("coverName",coverName);
        ArrayList<String> tags=state.getStringArrayList("tags");if(tags!=null){draftTags.clear();draftTags.addAll(tags);}
        tagInput.setText(state.getString("tagInput",""));String folder=state.getString("folder");if(folder!=null){int at=folderIds.indexOf(folder);if(at>=0)category.setSelection(at);}
    }
    private void addTag(){
        if(busy||!ready)return;
        String label=java.text.Normalizer.normalize(tagInput.getText().toString(),java.text.Normalizer.Form.NFKC).trim();
        if(label.isEmpty()||label.codePointCount(0,label.length())>32){tagInput.setError("请输入1至32个字符的标签");return;}
        for(int i=0;i<label.length();i++)if(Character.isISOControl(label.charAt(i))){tagInput.setError("标签不能包含控制字符");return;}
        for(String tag:draftTags)if(tag.equalsIgnoreCase(label)){tagInput.setError("这个标签已存在");return;}
        if(draftTags.size()>=24){tagInput.setError("每本书最多24个标签");return;}
        draftTags.add(label);tagInput.setText("");showTags();status.setText("标签已加入草稿，点“确定”后生效。");
    }
    private void showTags() {
        tags.removeAllViews();
        if (draftTags.isEmpty()) {
            tags.addView(Ui.text(this, "暂无标签", 15, Ui.MUTED));
        } else {
            for (String tag : draftTags) {
                final String target = tag;
                Button pill = Ui.button(this, "#" + tag,
                        () -> {
                            // 标签回流：intent 只带目标 tag，不带任何旧筛选状态（接收端 applyTagQuery
                            // 已把 favoritesOnly/folderId/tab/选择态归零）。finish() 关闭详情，保留正常返回栈。
                            Intent intent = new Intent(this, MainActivity.class);
                            intent.putExtra(MainActivity.EXTRA_TAG, target);
                            startActivity(intent);
                            finish();
                        },
                        "按标签" + tag + "筛选");
                // 视觉轻量：去掉 Ui.button 的默认 44dp 最小尺寸，收紧 padding/字号。
                pill.setMinHeight(0);
                pill.setMinimumHeight(0);
                pill.setMinWidth(0);
                pill.setMinimumWidth(0);
                pill.setTextSize(12);
                pill.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(0, 0, Ui.dp(this, 6), 0);
                pill.setLayoutParams(lp);
                tags.addView(pill);
            }
        }
        editTags.setEnabled(ready && !busy && !draftTags.isEmpty());
    }

    private void editTags() {
        if (busy || !ready || draftTags.isEmpty()) return;
        String[] labels = draftTags.toArray(new String[0]);
        boolean[] remove = new boolean[labels.length];
        dialog = new AlertDialog.Builder(this).setTitle("选择要移除的标签")
                .setMultiChoiceItems(labels, remove, (d, position, checked) -> remove[position] = checked)
                .setNegativeButton("取消", null)
                .setPositiveButton("移除选中", (d, which) -> {
                    boolean changed = false;
                    for (int i = 0; i < labels.length; i++) {
                        if (remove[i]) changed |= draftTags.remove(labels[i]);
                    }
                    if (changed) {
                        showTags();
                        status.setText("标签删除尚未保存。");
                    }
                }).show();
    }
    private void refreshPlaceholder(){String text=coverText.getText().toString().trim();coverPlaceholder.setText(text.isEmpty()?(title.length()==0?"无封面":title.getText()):text);coverPlaceholder.setVisibility(coverName.isEmpty()?View.VISIBLE:View.GONE);}
    private void refreshCover(){
        refreshPlaceholder();if(coverName.isEmpty()){cover.setImageDrawable(null);return;}
        final String selected=coverName;
        worker.execute(()->{Bitmap bitmap=null;try{bitmap=CoverFiles.preview(getApplicationContext(),selected,480);}catch(Exception ignored){}
            Bitmap ready=bitmap;runOnUiThread(()->{if(isDestroyed()||!selected.equals(coverName))return;cover.setImageBitmap(ready);coverPlaceholder.setVisibility(ready==null?View.VISIBLE:View.GONE);});});
    }
    private void pickCover(){
        if(busy||!ready)return;
        try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),PICK_COVER);}
        catch(android.content.ActivityNotFoundException error){status.setText("系统图片选择器不可用");}
    }
    private void chooseRelink(){
        if(busy||!ready)return;
        try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),RELINK_BOOK);}
        catch(android.content.ActivityNotFoundException error){status.setText("系统文件选择器不可用");}
    }
    private void relinkBook(Intent data){
        android.net.Uri uri=data.getData();
        try{
            if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            boolean persistent=false;
            for(android.content.UriPermission grant:getContentResolver().getPersistedUriPermissions())
                if(uri.equals(grant.getUri())&&grant.isReadPermission()){persistent=true;break;}
            if(!persistent)throw new java.io.IOException("该文件未提供长期读取授权，请用支持长期授权的文件选择器重新选择；不会复制文件作为替代。");
        }catch(Exception error){status.setText("无法重新关联："+message(error));return;}
        setBusy(true);status.setText("正在核对文件内容并重新关联…");
        worker.execute(()->{try{
            store.attachSource(bookId,uri,new android.os.CancellationSignal());
            BookStore.Book current=store.find(bookId);
            long measured;try{measured=store.size(current);}catch(Exception unavailable){measured=-1;}
            final long size=measured;
            List<BookStore.Source> sources=store.sources(bookId);
            StringBuilder info=new StringBuilder("存储方式：原文件引用，不在书库保留文件副本。\n大小：")
                    .append(size<0?"未知":android.text.format.Formatter.formatFileSize(this,size));
            for(int i=0;i<Math.min(5,sources.size());i++)info.append("\n原文件：").append(sourceLabel(sources.get(i)));
            info.append("\n修改书籍信息不重命名或改写原文件。");
            runOnUiThread(()->{if(isDestroyed())return;sourceInfo.setText(info);setBusy(false);status.setText("原文件已重新关联，书籍信息与阅读进度保留。");});
        }catch(Exception error){runOnUiThread(()->{if(isDestroyed())return;setBusy(false);status.setText("重新关联失败："+message(error)+"；现有书架记录保留。");});}});
    }
    private void discardDraftCover(){if(original!=null&&!coverName.equals(original.coverName))CoverFiles.discard(getApplicationContext(),coverName);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();
        if(request==RELINK_BOOK){relinkBook(data);return;}
        if(request==PICK_COVER){
            setBusy(true);status.setText("正在处理封面…");
            worker.execute(()->{try{
                String imported=CoverFiles.importImage(getApplicationContext(),uri);
                runOnUiThread(()->{if(isDestroyed()||isFinishing()){CoverFiles.discard(getApplicationContext(),imported);return;}
                    discardDraftCover();coverName=imported;refreshCover();setBusy(false);status.setText("封面已选，点“确定”后生效。");});
            }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){setBusy(false);status.setText("封面读取失败："+message(error));}});}});
        }else if(request==EXPORT_BOOK)writeExport(uri);
    }
    private void saveDraft(){
        if(busy||!ready)return;
        if(!tagInput.getText().toString().trim().isEmpty()){tagInput.setError("请先点击添加标签，或清空输入框再保存");tagInput.requestFocus();return;}
        final String checkedReview;
        try { checkedReview = BookReview.validate(review.getText().toString()); }
        catch (java.io.IOException error) { review.setError(error.getMessage()); review.requestFocus(); return; }
        String name=title.getText().toString(),writer=author.getText().toString(),intro=description.getText().toString(),caption=coverText.getText().toString(),image=coverName,folder=folderSelection();
        boolean star=favorite.isChecked();ArrayList<String> tags=new ArrayList<>(draftTags);
        setBusy(true);status.setText("正在保存书籍信息…");
        worker.execute(()->{try{
            metadata.save(bookId,name,writer,intro,caption,image,star,tags,folder!=null,folder,checkedReview);
            runOnUiThread(()->{if(isDestroyed()||isFinishing())return;setResult(RESULT_OK);baseline=snapshot();Toast.makeText(this,"书籍信息已保存",Toast.LENGTH_SHORT).show();finish();});
        }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){setBusy(false);status.setText("保存失败："+message(error));}});}});
    }
    private void setBusy(boolean value){
        busy=value;boolean enabled=ready&&!value;
        for(View view:new View[]{title,author,description,review,coverText,tagInput,favorite,category,save,chooseCover,removeCover,addTag,read,export,relink})view.setEnabled(enabled);
        showTags();
    }
    private void refreshStatistics(){
        if(original==null||statistics==null)return;SharedPreferences prefs=getSharedPreferences("reader",0);String id=original.book.id;
        int value=prefs.getInt("progress_"+id,0);String extra="";
        if(original.book.format.equals("pdf")){int count=prefs.getInt("pdf_count_"+id,0),page=prefs.getInt("pdf_page_"+id,0);value=count>0?Math.round(100f*(page+1)/count):0;}
        if(original.book.format.equals("epub"))extra="\n图文模式：第 "+(prefs.getInt("epub_chapter_"+id,0)+1)+" 章（位置独立保存）";
        long opened=prefs.getLong("opened_"+id,0);
        statistics.setText("阅读进度："+Math.max(0,Math.min(100,value))+"%"+extra+"\n最近阅读："+(opened==0?"尚未记录":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(opened))));
    }
    private static String sourceLabel(BookStore.Source source){try{return android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(source.uri));}catch(RuntimeException unavailable){return source.name;}}
    private boolean savedForAction(){if(dirty()||!tagInput.getText().toString().trim().isEmpty()){status.setText("请先保存修改，再阅读或导出书包。");return false;}return true;}
    /** 从上次读到的那一章接着听；PDF/漫画没有可朗读的文本层，如实说明而不是静默失败。 */
    private void openTts(){
        if(!ready||original==null||bookId==null)return;
        String format=original.book.format;
        if("pdf".equals(format)||"cbz".equals(format)){
            Toast.makeText(this,"PDF 与漫画暂不支持朗读",Toast.LENGTH_SHORT).show();return;
        }
        int chapter=getSharedPreferences("reader",0).getInt("chapter_"+bookId,0);
        startActivity(new Intent(this,TtsActivity.class)
                .putExtra(TtsPlaybackService.EXTRA_BOOK_ID,bookId)
                .putExtra(TtsPlaybackService.EXTRA_CHAPTER,Math.max(0,chapter)));
    }
    private void openBook(){
        if(busy||!ready||!savedForAction())return;
        try{BookStore.Book b=original.book;
            // 阶段 2-B：漫画走 ComicActivity（以 bookId 取回引用 URI），其余格式路由不变
            Class<?> reader="comic".equals(b.format)?ComicActivity.class
                    :b.format.equals("pdf")?PdfActivity.class:b.format.equals("epub")?EpubActivity.class:ReaderActivity.class;
            Intent intent=new Intent(this,reader).putExtra("bookId",b.id).putExtra("format",b.format).putExtra("title",b.title);
            getSharedPreferences("reader",0).edit().putLong("opened_"+b.id,System.currentTimeMillis()).apply();startActivity(intent);
        }catch(Exception error){status.setText("无法打开："+message(error));}
    }
    private void chooseExport(){
        if(busy||!ready||!savedForAction())return;
        EditText sharer=new EditText(this);sharer.setHint("分享者署名（可选，便于接收方归类想法）");
        sharer.setText(exportSharer);
        new AlertDialog.Builder(this).setTitle("导出书包").setMessage("署名会写入书包；对方导入遇到书评冲突时会以此归类。")
                .setView(sharer).setNegativeButton("取消",null)
                .setPositiveButton("继续",(d,w)->{
                    exportSharer=sharer.getText().toString().trim();
                    String name=original.book.title.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]","_").trim();
                    if(name.length()>80)name=name.substring(0,80);if(name.isEmpty())name="书籍";
                    try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,name+".olbook"),EXPORT_BOOK);}
                    catch(android.content.ActivityNotFoundException error){status.setText("系统保存入口不可用");}
                }).show();
    }
    private void writeExport(android.net.Uri destination){
        setBusy(true);status.setText("正在导出…");worker.execute(()->{String feedback;
            try{BookStore.Book b=store.find(bookId);try(BookAccess access=store.open(b);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new java.io.IOException("无法打开保存目标");TaggedBookPackage.write(out,access.file,b.title,b.format,store.tags(bookId),metadata.get(bookId).review,exportSharer);}feedback="已导出标签与书评书包";}
            catch(Exception error){feedback="导出失败："+message(error)+"；请勿使用未完成的文件。";}
            String result=feedback;runOnUiThread(()->{if(!isDestroyed()){setBusy(false);status.setText(result);}});
        });
    }
    @Override public void onBackPressed(){
        if(busy){Toast.makeText(this,"请等待当前操作完成",Toast.LENGTH_SHORT).show();return;}
        if(dirty()||tagInput.length()>0){dialog=new AlertDialog.Builder(this).setTitle("放弃未保存的修改？").setMessage("书籍信息、标签、书评和封面草稿都不会保存。")
                .setNegativeButton("继续编辑",null).setPositiveButton("放弃修改",(d,w)->{discardDraftCover();finish();}).show();}
        else finish();
    }
    @Override protected void onResume(){super.onResume();refreshStatistics();}
    @Override protected void onSaveInstanceState(Bundle state){
        if(ready){state.putString("title",title.getText().toString());state.putString("author",author.getText().toString());state.putString("description",description.getText().toString());state.putString("review",review.getText().toString());state.putString("coverText",coverText.getText().toString());
            state.putString("coverName",coverName);state.putBoolean("favorite",favorite.isChecked());state.putString("folder",folderSelection());state.putStringArrayList("tags",new ArrayList<>(draftTags));state.putString("tagInput",tagInput.getText().toString());}
        state.putInt("scrollY",scroll.getScrollY());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy(){if(dialog!=null)dialog.dismiss();if(isFinishing()&&dirty())discardDraftCover();worker.shutdown();super.onDestroy();}
    private static String message(Exception error){return error.getMessage()==null?"请重试":error.getMessage();}
}
