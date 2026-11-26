package org.openleaf.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import org.openleaf.reader.core.BookParser;
import org.openleaf.reader.core.BookSearch;
import org.openleaf.reader.core.LazyChapterBook;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Search previews never save reading progress. Only an explicit result tap returns an anchor. */
public final class BookSearchActivity extends Activity {
    private static final int LIMIT=10000;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
    private final ArrayList<Row> rows=new ArrayList<>();
    private final Set<Integer> collapsed=new HashSet<>();
    private LazyChapterBook book;
    private BookSearch.Result result;
    private EditText input;
    private Spinner scope, mode, nearWindow;
    private CheckBox caseSensitive;
    private TextView status,title;
    private ListView list;
    private Button previous,next;
    private ResultAdapter adapter;
    private Future<?> searching;
    private int generation,currentChapter,active=-1;
    private String bookId,initialQuery="",searchedQuery="";
    private boolean initialCase,searchedCase,initialChapterScope;
    private int searchedScope=-1,initialMode=BookSearch.LITERAL,searchedMode=BookSearch.LITERAL;
    private int initialWindow=BookSearch.DEFAULT_NEAR_WINDOW,searchedWindow=BookSearch.DEFAULT_NEAR_WINDOW;
    private final Runnable delayedSearch=this::performSearch;

    private static final class Row {
        final boolean header;
        final int chapter,count,hitIndex;
        Row(boolean header,int chapter,int count,int hitIndex){this.header=header;this.chapter=chapter;this.count=count;this.hitIndex=hitIndex;}
    }

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        bookId=getIntent().getStringExtra("bookId");
        if(bookId==null){finish();return;}
        currentChapter=getIntent().getIntExtra("currentChapter",0);
        initialQuery=getIntent().getStringExtra("query");if(initialQuery==null)initialQuery="";
        initialCase=getIntent().getBooleanExtra("caseSensitive",false);
        initialChapterScope=getIntent().getIntExtra("scope",-1)>=0;
        initialMode=getIntent().getIntExtra("searchMode",BookSearch.LITERAL);
        initialWindow=getIntent().getIntExtra("nearWindow",BookSearch.DEFAULT_NEAR_WINDOW);
        if(state!=null){
            initialQuery=state.getString("query",initialQuery);initialCase=state.getBoolean("case",initialCase);
            initialChapterScope=state.getBoolean("chapterScope",initialChapterScope);active=state.getInt("active",-1);
            initialMode=state.getInt("mode",initialMode);initialWindow=state.getInt("window",initialWindow);
            int[] closed=state.getIntArray("collapsed");if(closed!=null)for(int chapter:closed)collapsed.add(chapter);
        }
        build();loadBook();
    }

    private void build(){
        LinearLayout root=Ui.column(this);Ui.mount(this,root);root.setBackgroundColor(0xFFF3F3F1);
        LinearLayout header=Ui.column(this);Ui.pad(header,14);root.addView(header);
        LinearLayout nav=Ui.row(this);nav.addView(Ui.button(this,"返回",this::finish));
        nav.addView(Ui.title(this,"书内查找",21),Ui.weight());header.addView(nav);
        LinearLayout search=Ui.row(this);input=new EditText(this);input.setSingleLine(true);input.setTextSize(17);
        input.setHint("输入本书关键词");input.setContentDescription("书内搜索关键词");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(BookSearch.MAX_QUERY_LENGTH)});
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);input.setText(initialQuery);
        search.addView(input,Ui.weight());search.addView(Ui.button(this,"清除",()->input.setText("")));
        search.addView(Ui.button(this,"搜索",()->{hideKeyboard();queueSearch(false);}));header.addView(search);
        LinearLayout options=Ui.row(this);scope=new Spinner(this);
        scope.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全书","当前章节"}));
        scope.setSelection(initialChapterScope?1:0);options.addView(scope,Ui.weight());
        caseSensitive=new CheckBox(this);caseSensitive.setText("区分大小写");caseSensitive.setChecked(initialCase);caseSensitive.setTextColor(Ui.INK);
        options.addView(caseSensitive,Ui.weight());header.addView(options);
        LinearLayout modes=Ui.row(this);
        mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,new String[]{"字面","正则","全词","附近词"}));
        mode.setSelection(Math.max(0,Math.min(3,initialMode)));modes.addView(mode,Ui.weight());
        nearWindow=new Spinner(this);nearWindow.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,new String[]{"32 字","64 字","128 字","256 字"}));
        int[] windows={32,64,128,256};int windowPos=0;
        for(int i=0;i<windows.length;i++)if(windows[i]==initialWindow)windowPos=i;
        nearWindow.setSelection(windowPos);nearWindow.setVisibility(initialMode==BookSearch.NEAR?View.VISIBLE:View.GONE);
        modes.addView(nearWindow,Ui.weight());header.addView(modes);
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                nearWindow.setVisibility(pos==BookSearch.NEAR?View.VISIBLE:View.GONE);
                input.setHint(pos==BookSearch.NEAR?"输入多个词，用空格分隔":pos==BookSearch.REGEX?"输入正则表达式":"输入本书关键词");
                queueSearch(false);
            }
        });
        nearWindow.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){queueSearch(false);}
        });
        title=Ui.title(this,"正在读取书籍…",16);title.setMaxLines(2);header.addView(title);
        status=Ui.text(this,"",13,Ui.MUTED);status.setMaxLines(3);header.addView(status);
        list=new ListView(this);list.setDivider(null);adapter=new ResultAdapter();list.setAdapter(adapter);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=Ui.row(this);Ui.pad(footer,6);
        previous=Ui.button(this,"上一个",()->navigate(-1));next=Ui.button(this,"下一个",()->navigate(1));
        footer.addView(previous,Ui.weight());footer.addView(next,Ui.weight());root.addView(footer);navigationState();
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){queueSearch(true);}
            public void afterTextChanged(Editable value){}
        });
        input.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){hideKeyboard();queueSearch(false);return true;}return false;});
        caseSensitive.setOnCheckedChangeListener((v,checked)->queueSearch(false));
        scope.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){queueSearch(false);}
        });
        list.setOnItemClickListener((p,v,index,id)->{
            Row row=rows.get(index);
            if(row.header){if(!collapsed.add(row.chapter))collapsed.remove(row.chapter);flatten();}
            else returnHit(row.hitIndex);
        });
    }

    private void loadBook(){
        worker.execute(()->{
            try{
                BookStore store=new BookStore(this);BookStore.Book record=store.find(bookId);
                LazyChapterBook parsed;
                try(BookAccess access=store.open(record)){parsed=LazyChapterBook.open(access.file,record.format,getCacheDir());}
                main.post(()->{if(isDestroyed()){parsed.close();return;}book=parsed;currentChapter=Math.max(0,Math.min(currentChapter,book.size()-1));
                    title.setText(record.title);queueSearch(false);});
            }catch(Exception error){main.post(()->{if(!isDestroyed()){title.setText("无法读取书籍");status.setText(error.getMessage()==null?"请返回后重试":error.getMessage());}});}
        });
    }

    private void queueSearch(boolean debounce){
        generation++;main.removeCallbacks(delayedSearch);
        if(searching!=null){searching.cancel(true);worker.purge();}
        result=null;rows.clear();adapter.notifyDataSetChanged();navigationState();
        String query=input.getText().toString();
        if(query.isEmpty()){status.setText("输入关键词，查找本书正文。不会搜索其他书籍。");return;}
        if(book==null){status.setText("正在读取书籍…");return;}
        status.setText("正在查找…");
        if(debounce)main.postDelayed(delayedSearch,300);else performSearch();
    }

    private void performSearch(){
        if(book==null||isDestroyed())return;
        final int request=++generation;
        final String query=input.getText().toString();if(query.isEmpty())return;
        final boolean matchCase=caseSensitive.isChecked();final int chapterScope=scope.getSelectedItemPosition()==1?currentChapter:-1;
        final int useMode=mode.getSelectedItemPosition();
        final int[] windowValues={32,64,128,256};final int useWindow=windowValues[nearWindow.getSelectedItemPosition()];
        searching=worker.submit(()->{
            try{
                BookSearch.Result found=BookSearch.find(book,query,matchCase,chapterScope,LIMIT,
                        ()->Thread.currentThread().isInterrupted(),useMode,useWindow);
                main.post(()->{
                    if(isDestroyed()||request!=generation)return;
                    result=found;searchedQuery=query;searchedCase=matchCase;searchedScope=chapterScope;
                    searchedMode=useMode;searchedWindow=useWindow;
                    active=found.hits.isEmpty()?-1:Math.max(0,Math.min(active,found.hits.size()-1));
                    Set<Integer> chapters=new HashSet<>();for(BookSearch.Hit hit:found.hits)chapters.add(hit.chapter);
                    status.setText(found.hits.isEmpty()?"没有找到匹配内容":
                        "找到 "+found.hits.size()+" 处 · "+chapters.size()+" 章"+(found.truncated?"（结果超过10000处，仅显示前10000处）":"")+" · 点击结果跳到正文");
                    flatten();navigationState();
                });
            }catch(CancellationException ignored){}
            catch(Exception error){main.post(()->{if(!isDestroyed()&&request==generation){status.setText("查找失败："+error.getMessage());navigationState();}});}
        });
    }

    private void flatten(){
        rows.clear();
        if(result!=null){
            int i=0;
            while(i<result.hits.size()){
                int start=i,chapter=result.hits.get(i).chapter;
                while(i<result.hits.size()&&result.hits.get(i).chapter==chapter)i++;
                rows.add(new Row(true,chapter,i-start,-1));
                if(!collapsed.contains(chapter))for(int j=start;j<i;j++)rows.add(new Row(false,chapter,0,j));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void navigate(int direction){
        if(result==null||result.hits.isEmpty())return;
        active=active<0?0:(active+direction+result.hits.size())%result.hits.size();
        int chapter=result.hits.get(active).chapter;collapsed.remove(chapter);flatten();
        for(int i=0;i<rows.size();i++)if(rows.get(i).hitIndex==active){list.smoothScrollToPosition(i);break;}
        status.setText("第 "+(active+1)+" / "+result.hits.size()+" 处 · 点击卡片跳到正文");
    }

    private void returnHit(int index){
        if(result==null||index<0||index>=result.hits.size())return;
        BookSearch.Hit hit=result.hits.get(index);
        setResult(RESULT_OK,new Intent().putExtra("chapter",hit.chapter).putExtra("start",hit.start).putExtra("end",hit.end)
            .putExtra("query",searchedQuery).putExtra("caseSensitive",searchedCase).putExtra("scope",searchedScope)
            .putExtra("searchMode",searchedMode).putExtra("nearWindow",searchedWindow));finish();
    }

    private void navigationState(){boolean enabled=result!=null&&!result.hits.isEmpty();previous.setEnabled(enabled);next.setEnabled(enabled);}
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);}
    private SpannableString highlight(BookSearch.Hit hit){
        String prefix=hit.snippetStart>0?"…":"",suffix=hit.snippetStart+hit.snippet.length()<book.meta(hit.chapter).charCount?"…":"";
        SpannableString marked=new SpannableString(prefix+hit.snippet+suffix);
        int shift=prefix.length()-hit.snippetStart;
        for(int k=0;k<hit.matchStarts.length;k++){
            int start=Math.max(prefix.length(),Math.min(marked.length(),hit.matchStarts[k]+shift));
            int end=Math.max(start,Math.min(marked.length(),hit.matchEnds[k]+shift));
            if(end<=start)continue;
            marked.setSpan(new ForegroundColorSpan(0xFFB33436),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            marked.setSpan(new StyleSpan(Typeface.BOLD),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return marked;
    }

    private final class ResultAdapter extends BaseAdapter {
        public int getCount(){return rows.size();}
        public Row getItem(int position){return rows.get(position);}
        public long getItemId(int position){return position;}
        public int getViewTypeCount(){return 2;}
        public int getItemViewType(int position){return rows.get(position).header?0:1;}
        public View getView(int position,View recycled,ViewGroup parent){
            Row row=rows.get(position);TextView label=recycled instanceof TextView?(TextView)recycled:new TextView(BookSearchActivity.this);
            label.setTextColor(Ui.INK);label.setGravity(Gravity.CENTER_VERTICAL);label.setMinHeight(Ui.dp(BookSearchActivity.this,52));
            label.setPadding(Ui.dp(BookSearchActivity.this,18),Ui.dp(BookSearchActivity.this,12),Ui.dp(BookSearchActivity.this,18),Ui.dp(BookSearchActivity.this,12));
            if(row.header){
                label.setTextSize(16);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);label.setMaxLines(2);
                label.setBackgroundColor(0xFFE6E6E3);
                label.setText((collapsed.contains(row.chapter)?"▸ ":"▾ ")+book.meta(row.chapter).title+"  ·  "+row.count+"处");
            }else{
                label.setTextSize(17);label.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);label.setMaxLines(4);label.setLineSpacing(Ui.dp(BookSearchActivity.this,4),1.15f);
                label.setBackground(Ui.shape(row.hitIndex==active?0xFFFFF3DC:Color.WHITE,12,BookSearchActivity.this));
                label.setText(highlight(result.hits.get(row.hitIndex)));
            }
            return label;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("query",input.getText().toString());out.putBoolean("case",caseSensitive.isChecked());out.putBoolean("chapterScope",scope.getSelectedItemPosition()==1);out.putInt("active",active);
        out.putInt("mode",mode.getSelectedItemPosition());
        int[] windowValues={32,64,128,256};out.putInt("window",windowValues[nearWindow.getSelectedItemPosition()]);
        int[] closed=new int[collapsed.size()];int i=0;for(int chapter:collapsed)closed[i++]=chapter;out.putIntArray("collapsed",closed);super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy(){generation++;main.removeCallbacksAndMessages(null);if(searching!=null)searching.cancel(true);worker.shutdownNow();if(book!=null)book.close();super.onDestroy();}
}
