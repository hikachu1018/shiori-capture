package jp.shiori.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.InputStream;
import java.util.List;

/** Capture controls and the private, offline bookshelf. */
public final class MainActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable refresh=new Runnable(){@Override public void run(){refreshStatus();if(lastRunning&&!CaptureService.running)refreshBooks();lastRunning=CaptureService.running;handler.postDelayed(this,1000);}};
 private BookStore db;private LinearLayout content,books;private TextView status,access,destination;
 private EditText title;private RadioGroup writing,direction;private Spinner countdown,interval;private Button start;
 private boolean lastRunning;private long pendingExportBook=-1;
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(7),0,dp(7));return v;}
 private Button button(String name,Runnable action){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setMinHeight(dp(50));b.setOnClickListener(v->action.run());return b;}
 private void heading(String s){TextView v=text(s,19,0xff122332);v.setTypeface(null,Typeface.BOLD);content.addView(v);}
 private RadioGroup choices(String a,String b){RadioGroup g=new RadioGroup(this);g.setOrientation(LinearLayout.HORIZONTAL);for(String label:new String[]{a,b}){RadioButton x=new RadioButton(this);x.setText(label);x.setId(View.generateViewId());g.addView(x,new LinearLayout.LayoutParams(0,dp(52),1));}g.check(g.getChildAt(0).getId());content.addView(g);return g;}
 private Spinner spinner(String[] options){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));content.addView(s,new LinearLayout.LayoutParams(-1,dp(48)));return s;}
 private void show(String message){new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK",null).show();}
 @Override public void onCreate(Bundle state){super.onCreate(state);db=new BookStore(this);
  if(Store.prefs(this).getBoolean("running",false)&&CaptureService.instance==null){
   long id=Store.prefs(this).getLong("runningBookId",-1);if(id>0)db.markPartial(id);
   Store.prefs(this).edit().putBoolean("running",false).remove("runningBookId").apply();
   Store.status(this,"前回の撮影が中断されました。保存済みの本文は本棚に残っています。");
  }
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff3f6f8);
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(20),dp(22),dp(32));scroll.addView(content);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((v,in)->{Insets i=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom);return in;});
  TextView app=text("しおり Capture",28,0xff122332);app.setTypeface(null,Typeface.BOLD);content.addView(app);
  content.addView(text("撮影から章ごとの読書まで、端末内で完結します。",15,0xff536577));
  status=text("",15,0xff176f62);status.setPadding(dp(14),dp(14),dp(14),dp(14));status.setBackgroundColor(0xffe0f1ec);content.addView(status);
  heading("本棚");content.addView(button("旧版の章付きEPUBを取り込む",this::pickEpub));
  books=new LinearLayout(this);books.setOrientation(LinearLayout.VERTICAL);content.addView(books);
  heading("新しい本を撮影");content.addView(text("本の名前",15,0xff24384b));
  title=new EditText(this);title.setSingleLine(true);title.setHint("例：本のタイトル");content.addView(title);
  access=text("",14,0xff536577);content.addView(access);
  content.addView(button("撮影とページ送りの権限を設定",()->new AlertDialog.Builder(this).setTitle("ユーザー補助の権限")
   .setMessage("Kindleの画面撮影とページ送りのために使います。本文は端末内で処理・保存します。次の画面で「しおり Capture」をオンにしてください。")
   .setPositiveButton("設定へ",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("戻る",null).show()));
  content.addView(text("本文の向き",15,0xff24384b));writing=choices("縦書き","横書き");
  content.addView(text("次ページへ送る指の方向",15,0xff24384b));direction=choices("右へ →","← 左へ");
  content.addView(text("開始までの準備時間",15,0xff24384b));countdown=spinner(new String[]{"30秒","60秒","10秒"});
  content.addView(text("ページ送り後の待ち時間",15,0xff24384b));interval=spinner(new String[]{"0.6秒（速い）","1秒（標準）","2秒（安定）"});interval.setSelection(1);
  content.addView(text("初回は3画面ほどで手動停止し、OCRと章立てを確認してください。同じ画面が続くと自動停止します。",14,0xff536577));
  start=button("撮影を開始",this::beginCapture);start.setTextColor(Color.WHITE);start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff176f62));content.addView(start);
  content.addView(button("撮影を停止",()->{if(CaptureService.instance!=null)CaptureService.instance.requestStop("手動で停止しました。");}));
  heading("必要なときに書き出す");destination=text("",14,0xff536577);content.addView(destination);
  content.addView(button("書き出し先フォルダを選ぶ",this::pickFolder));
  content.addView(text("本棚の「書き出す」から、章付きEPUBと本全体の本文PDFを作れます。画像PDFや章別PDFは作りません。",14,0xff536577));
  content.addView(text("試用版 0.3.0 · Android 11以降",13,0xff536577));
  refreshBooks();refreshStatus();
 }
 private void beginCapture(){
  CaptureService service=CaptureService.instance;if(service==null){show("先にユーザー補助の権限をオンにしてください。");return;}
  if(CaptureService.running){show("撮影中です。");return;}
  String name=title.getText().toString().trim();if(name.isEmpty()){show("本の名前を入力してください。");return;}
  long id=db.createBook(name);
  service.begin(id,writing.getCheckedRadioButtonId()==writing.getChildAt(0).getId(),direction.getCheckedRadioButtonId()==direction.getChildAt(0).getId(),new int[]{30,60,10}[countdown.getSelectedItemPosition()],new int[]{600,1000,2000}[interval.getSelectedItemPosition()]);
  if(!CaptureService.running){db.markPartial(id);refreshBooks();show("撮影を開始できませんでした。状態欄を確認してください。");return;}
  Intent kindle=getPackageManager().getLaunchIntentForPackage("com.amazon.kindlefs");if(kindle==null)kindle=getPackageManager().getLaunchIntentForPackage("com.amazon.kindle");
  if(kindle!=null){try{startActivity(kindle);}catch(Exception e){show("Kindleを手動で開いてください。");}}else show("準備時間中にKindleを手動で開いてください。");
 }
 private void refreshStatus(){
  status.setText(Store.prefs(this).getString("status","本を選ぶか、新しく撮影してください。"));
  access.setText(CaptureService.instance==null?"撮影権限：未設定":"撮影権限：許可済み");
  destination.setText(Store.prefs(this).getString("tree","").isEmpty()?"書き出し先：未選択":"書き出し先：選択済み");
  start.setEnabled(!CaptureService.running);
 }
 private void refreshBooks(){
  if(books==null)return;books.removeAllViews();List<BookStore.Book> list=db.listBooks();
  if(list.isEmpty()){books.addView(text("本はまだありません。",14,0xff536577));return;}
  for(BookStore.Book b:list){
   books.addView(text(b.title+(b.state.equals("partial")?" 〔途中まで〕":b.state.equals("capturing")?" 〔撮影中〕":""),17,0xff24384b));
   if(b.state.equals("capturing")){books.addView(text("撮影終了後に読書と章編集ができます。",14,0xff536577));continue;}
   LinearLayout first=new LinearLayout(this);
   first.addView(button("続きから読む",()->openReader(b.id,-1)),new LinearLayout.LayoutParams(0,dp(50),1));
   first.addView(button("章を選ぶ",()->chooseChapter(b.id)),new LinearLayout.LayoutParams(0,dp(50),1));books.addView(first);
   LinearLayout second=new LinearLayout(this);
   second.addView(button("章を編集",()->{Intent i=new Intent(this,ChapterEditorActivity.class);i.putExtra("bookId",b.id);startActivity(i);}),new LinearLayout.LayoutParams(0,dp(50),1));
   second.addView(button("書き出す",()->exportBook(b.id)),new LinearLayout.LayoutParams(0,dp(50),1));books.addView(second);
   books.addView(button("本の名前を変更",()->rename(b)));
  }
 }
 private void rename(BookStore.Book book){
  EditText input=new EditText(this);input.setSingleLine(true);input.setText(book.title);
  new AlertDialog.Builder(this).setTitle("本の名前").setView(input).setPositiveButton("保存",(d,w)->{
   try{db.renameBook(book.id,input.getText().toString());refreshBooks();}catch(Exception e){show(e.getMessage());}
  }).setNegativeButton("戻る",null).show();
 }
 private void openReader(long bookId,int chapterStart){Intent i=new Intent(this,ReaderActivity.class);i.putExtra("bookId",bookId);if(chapterStart>=0)i.putExtra("chapterStart",chapterStart);startActivity(i);}
 private void chooseChapter(long bookId){
  List<BookStore.Chapter> chapters=db.listChapters(bookId);if(chapters.isEmpty()){show("章がありません。");return;}
  String[] names=new String[chapters.size()];for(int i=0;i<names.length;i++)names[i]=chapters.get(i).title;
  new AlertDialog.Builder(this).setTitle("読み始める章").setItems(names,(d,which)->openReader(bookId,chapters.get(which).startSeq)).setNegativeButton("戻る",null).show();
 }
 private void pickFolder(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,10);}
 private void pickEpub(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/epub+zip");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,11);}
 private void exportBook(long bookId){
  String tree=Store.prefs(this).getString("tree","");
  if(tree.isEmpty()){pendingExportBook=bookId;pickFolder();return;}
  status.setText("EPUBと本文PDFを書き出しています…");Uri target=Uri.parse(tree);
  new Thread(()->{
   String result;
   try(BookStore workerDb=new BookStore(this)){
    String epub=BookArchive.exportEpub(this,workerDb,bookId,target);
    String pdf=BookArchive.exportPdf(this,workerDb,bookId,target);
    result="書き出しました：\n"+epub+"\n"+pdf;
   }catch(Exception e){result="書き出しエラー: "+e.getMessage();}
   final String message=result;runOnUiThread(()->{Store.status(this,message);refreshStatus();show(message);});
  },"shiori-export").start();
 }
 private String displayName(Uri uri){
  try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}
  catch(Exception ignored){}
  return "取り込んだ本.epub";
 }
 private void importEpub(Uri uri){
  String name=displayName(uri);status.setText("EPUBを取り込んでいます…");
  new Thread(()->{
   String result;
   try(InputStream input=getContentResolver().openInputStream(uri);BookStore workerDb=new BookStore(this)){
    if(input==null)throw new IllegalStateException("ファイルを開けません");
    BookArchive.Imported imported=BookArchive.importOldEpub(input,name);
    workerDb.importBook(imported);result="「"+imported.title+"」を本棚に追加しました。";
   }catch(Exception e){result="取り込みエラー: "+e.getMessage();}
   final String message=result;runOnUiThread(()->{Store.status(this,message);refreshStatus();refreshBooks();show(message);});
  },"shiori-import").start();
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
  if(request==10){
   try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));Store.prefs(this).edit().putString("tree",uri.toString()).apply();refreshStatus();if(pendingExportBook>0){long id=pendingExportBook;pendingExportBook=-1;exportBook(id);}}
   catch(Exception e){show("このフォルダの保存権限を取得できませんでした。");}
  }else if(request==11)importEpub(uri);
 }
 @Override protected void onResume(){super.onResume();refreshBooks();handler.removeCallbacks(refresh);handler.post(refresh);}
 @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
 @Override protected void onDestroy(){handler.removeCallbacks(refresh);if(db!=null)db.close();super.onDestroy();}
}
