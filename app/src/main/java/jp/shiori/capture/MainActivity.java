package jp.shiori.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Insets;
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
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.InputStream;
import java.util.List;

/** The bookshelf and capture setup are separate, focused views. */
public final class MainActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable refresh=new Runnable(){@Override public void run(){refreshStatus();if(lastRunning&&!CaptureService.running)render();lastRunning=CaptureService.running;handler.postDelayed(this,1000);}};
 private BookStore db;private LinearLayout content,footer;private TextView status,access,destination;
 private EditText title;private RadioGroup writing,direction;private Spinner countdown,interval;
 private Button libraryTab,captureTab,primaryAction;private boolean captureSelected,lastRunning;
 private String draftTitle="";private boolean draftVertical=true,draftRight=true;private int draftCountdown=0,draftInterval=1;
 private long pendingExportBook=-1;
 private int dp(int n){return Ui.dp(this,n);}
 private TextView text(String value,int size,int color,boolean bold){return Ui.text(this,value,size,color,bold);}
 private void message(String value){new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK",null).show();}
 private void heading(LinearLayout parent,String value){parent.addView(text(value,20,Ui.INK,true),Ui.margins(this,24,12));}
 private void addButton(LinearLayout parent,String label,int style,Runnable action,int top){parent.addView(Ui.button(this,label,style,action),Ui.margins(this,top,0));}
 private LinearLayout horizontal(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);return row;}
 private void pair(LinearLayout parent,Button left,Button right){
  LinearLayout row=horizontal();LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,dp(50),1);a.rightMargin=dp(8);
  row.addView(left,a);row.addView(right,new LinearLayout.LayoutParams(0,dp(50),1));parent.addView(row,Ui.margins(this,10,0));
 }
 @Override public void onCreate(Bundle state){super.onCreate(state);db=new BookStore(this);
  if(!CaptureService.running){boolean interrupted=false;for(BookStore.Book book:db.listBooks())if("capturing".equals(book.state)){db.markPartial(book.id);interrupted=true;}
   Store.prefs(this).edit().putBoolean("running",false).remove("runningBookId").apply();
   if(interrupted)Store.status(this,"前回の撮影が中断されました。本棚から撮影を再開できます。");}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BACKGROUND);setContentView(root);
  root.setOnApplyWindowInsetsListener((v,in)->{Insets i=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom);return in;});
  LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(20),dp(17),dp(20),dp(12));root.addView(top);
  top.addView(text("しおり Capture",23,Ui.INK,true));
  TextView subtitle=text("撮影して、章から読む",13,Ui.MUTED,false);top.addView(subtitle,Ui.margins(this,3,0));
  LinearLayout tabs=horizontal();tabs.setPadding(dp(20),0,dp(20),dp(9));root.addView(tabs);
  libraryTab=Ui.button(this,"本棚",Ui.PLAIN,()->selectTab(false));captureTab=Ui.button(this,"新しく撮影",Ui.PLAIN,()->selectTab(true));
  LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(48),1);left.rightMargin=dp(8);tabs.addView(libraryTab,left);tabs.addView(captureTab,new LinearLayout.LayoutParams(0,dp(48),1));
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(6),dp(20),dp(28));scroll.addView(content);
  footer=new LinearLayout(this);footer.setPadding(dp(20),dp(10),dp(20),dp(12));footer.setBackground(Ui.background(this,Ui.SURFACE,0,0));root.addView(footer);
  captureSelected=state!=null&&state.getBoolean("captureSelected",false);render();
 }
 @Override protected void onSaveInstanceState(Bundle out){saveDraft();out.putBoolean("captureSelected",captureSelected);super.onSaveInstanceState(out);}
 private void saveDraft(){if(title==null)return;draftTitle=title.getText().toString();draftVertical=currentVertical();draftRight=currentRight();draftCountdown=countdown.getSelectedItemPosition();draftInterval=interval.getSelectedItemPosition();}
 private void selectTab(boolean capture){if(captureSelected==capture)return;saveDraft();captureSelected=capture;render();}
 private void updateTabs(){
  libraryTab.setBackground(Ui.background(this,captureSelected?Ui.BACKGROUND:Ui.GREEN_SOFT,13,0));
  captureTab.setBackground(Ui.background(this,captureSelected?Ui.GREEN_SOFT:Ui.BACKGROUND,13,0));
  libraryTab.setTextColor(captureSelected?Ui.MUTED:Ui.GREEN);captureTab.setTextColor(captureSelected?Ui.GREEN:Ui.MUTED);
 }
 private void render(){if(content==null)return;if(captureSelected)saveDraft();content.removeAllViews();footer.removeAllViews();status=null;access=null;destination=null;
  updateTabs();if(captureSelected)renderCapture();else renderLibrary();
  primaryAction=Ui.button(this,captureSelected?(CaptureService.running?"撮影を一時停止":"撮影を開始"):"新しい本を撮影",Ui.PRIMARY,()->{
   if(!captureSelected)selectTab(true);else if(CaptureService.running){if(CaptureService.instance!=null)CaptureService.instance.requestStop("一時停止しました。");}else beginCapture();
  });footer.addView(primaryAction,new LinearLayout.LayoutParams(-1,dp(54)));refreshStatus();
 }
 private void addStatus(){
  status=text("",14,Ui.GREEN,false);status.setPadding(dp(14),dp(12),dp(14),dp(12));status.setBackground(Ui.background(this,Ui.GREEN_SOFT,12,0));
  content.addView(status,Ui.margins(this,10,6));
 }
 private void renderLibrary(){
  content.addView(text("本棚",27,Ui.INK,true));content.addView(text("続きから読む本を選んでください。",14,Ui.MUTED,false),Ui.margins(this,5,0));addStatus();
  List<BookStore.Book> list=db.listBooks();
  if(list.isEmpty()){
   LinearLayout empty=Ui.card(this);empty.addView(text("本はまだありません",20,Ui.INK,true));
   empty.addView(text("まず本を撮影するか、旧版の章付きEPUBを取り込んでください。",14,Ui.MUTED,false),Ui.margins(this,8,0));
   content.addView(empty,Ui.margins(this,20,0));
  }else for(BookStore.Book book:list)addBookCard(book);
  heading(content,"本棚の管理");
  LinearLayout tools=Ui.card(this);addButton(tools,"旧版の章付きEPUBを取り込む",Ui.SECONDARY,this::pickEpub,0);
  destination=text("",13,Ui.MUTED,false);tools.addView(destination,Ui.margins(this,16,0));
  addButton(tools,"書き出し先フォルダを選ぶ",Ui.PLAIN,this::pickFolder,9);content.addView(tools);
  content.addView(text("試用版 0.3.3 · Android 11以降",12,Ui.MUTED,false),Ui.margins(this,18,0));
 }
 private void addBookCard(BookStore.Book book){
  LinearLayout card=Ui.card(this);content.addView(card,Ui.margins(this,12,0));
  card.addView(text(book.title,20,Ui.INK,true));
  String badge="partial".equals(book.state)?"途中まで保存":"capturing".equals(book.state)?"撮影中":"読書できます";
  TextView state=text(badge,13,"partial".equals(book.state)?Ui.WARNING:Ui.GREEN,true);
  state.setPadding(dp(10),dp(5),dp(10),dp(5));state.setBackground(Ui.background(this,"partial".equals(book.state)?Ui.WARNING_SOFT:Ui.GREEN_SOFT,10,0));
  card.addView(state,Ui.margins(this,10,0));
  if("capturing".equals(book.state)){card.addView(text("撮影が終わると読書と章編集ができます。",14,Ui.MUTED,false),Ui.margins(this,12,0));return;}
  int chapters=db.listChapters(book.id).size();int screens=db.countScreens(book.id);
  String detail=chapters+"章"+(screens>0?" · "+screens+"画面保存":"");
  card.addView(text(detail,13,Ui.MUTED,false),Ui.margins(this,9,0));
  if("partial".equals(book.state))addButton(card,"撮影を再開",Ui.PRIMARY,()->resumeCapture(book),14);
  else addButton(card,"続きから読む",Ui.PRIMARY,()->openReader(book.id,-1),14);
  Button first=Ui.button(this,"章を選ぶ",Ui.SECONDARY,()->chooseChapter(book.id));
  Button second=Ui.button(this,"partial".equals(book.state)?"途中まで読む":"その他",Ui.PLAIN,()->{
   if("partial".equals(book.state))openReader(book.id,-1);else showBookMenu(book,card);
  });
  pair(card,first,second);
  if("partial".equals(book.state))addButton(card,"章編集・書き出しなど",Ui.PLAIN,()->showBookMenu(book,card),8);
 }
 private void showBookMenu(BookStore.Book book,View anchor){
  PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add("章を編集").setOnMenuItemClickListener(item->{Intent i=new Intent(this,ChapterEditorActivity.class);i.putExtra("bookId",book.id);startActivity(i);return true;});
  menu.getMenu().add("EPUB・PDFを書き出す").setOnMenuItemClickListener(item->{exportBook(book.id);return true;});
  menu.getMenu().add("本の名前を変更").setOnMenuItemClickListener(item->{rename(book);return true;});menu.show();
 }
 private RadioGroup choices(LinearLayout parent,String first,String second,boolean firstSelected){
  RadioGroup group=new RadioGroup(this);group.setOrientation(LinearLayout.HORIZONTAL);
  for(String label:new String[]{first,second}){RadioButton option=new RadioButton(this);option.setText(label);option.setTextSize(15);option.setTextColor(Ui.INK);option.setId(View.generateViewId());group.addView(option,new RadioGroup.LayoutParams(0,dp(52),1));}
  group.check(group.getChildAt(firstSelected?0:1).getId());parent.addView(group,Ui.margins(this,5,0));return group;
 }
 private Spinner spinner(String[] values,int selected){Spinner result=new Spinner(this);result.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));result.setSelection(selected);return result;}
 private void renderCapture(){
  content.addView(text("新しい本を撮影",27,Ui.INK,true));content.addView(text("設定してからKindleを開きます。",14,Ui.MUTED,false),Ui.margins(this,5,0));addStatus();
  LinearLayout permission=Ui.card(this);access=text("",16,Ui.INK,true);permission.addView(access);
  permission.addView(text("画面の撮影とページ送りに必要です。本文は端末内に保存します。",13,Ui.MUTED,false),Ui.margins(this,6,0));
  addButton(permission,"権限を設定する",Ui.SECONDARY,()->new AlertDialog.Builder(this).setTitle("ユーザー補助の権限")
   .setMessage("Kindleの画面撮影とページ送りのために使います。次の画面で「しおり Capture」をオンにしてください。")
   .setPositiveButton("設定へ",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("戻る",null).show(),12);
  content.addView(permission,Ui.margins(this,14,0));
  LinearLayout details=Ui.card(this);details.addView(text("本とページ送り",18,Ui.INK,true));
  details.addView(text("本の名前",14,Ui.MUTED,true),Ui.margins(this,15,3));
  title=new EditText(this);title.setSingleLine(true);title.setHint("例：本のタイトル");title.setText(draftTitle);title.setTextSize(17);details.addView(title);
  details.addView(text("本文の向き",14,Ui.MUTED,true),Ui.margins(this,16,0));writing=choices(details,"縦書き","横書き",draftVertical);
  details.addView(text("次ページへ送る指の方向",14,Ui.MUTED,true),Ui.margins(this,12,0));direction=choices(details,"右へスワイプ","左へスワイプ",draftRight);
  content.addView(details,Ui.margins(this,12,0));
  LinearLayout timing=Ui.card(this);timing.addView(text("撮影のタイミング",18,Ui.INK,true));
  timing.addView(text("Kindleを開くまでの準備時間",14,Ui.MUTED,true),Ui.margins(this,14,2));
  countdown=spinner(new String[]{"30秒","60秒","10秒"},draftCountdown);timing.addView(countdown,new LinearLayout.LayoutParams(-1,dp(48)));
  timing.addView(text("ページ送り後の待ち時間",14,Ui.MUTED,true),Ui.margins(this,10,2));
  interval=spinner(new String[]{"0.6秒（速い）","1秒（標準）","2秒（安定）"},draftInterval);timing.addView(interval,new LinearLayout.LayoutParams(-1,dp(48)));
  content.addView(timing,Ui.margins(this,12,0));
  LinearLayout help=Ui.card(this);help.addView(text("最初の撮影は3画面ほどで確認",16,Ui.INK,true));
  help.addView(text("Kindle画面上の「一時停止」で保存できます。本棚で本文と章を確認した後、同じ本の続きから再開できます。",13,Ui.MUTED,false),Ui.margins(this,7,0));
  content.addView(help,Ui.margins(this,12,0));
 }
 private boolean currentVertical(){return writing==null?draftVertical:writing.getCheckedRadioButtonId()==writing.getChildAt(0).getId();}
 private boolean currentRight(){return direction==null?draftRight:direction.getCheckedRadioButtonId()==direction.getChildAt(0).getId();}
 private int currentWait(){return new int[]{600,1000,2000}[interval==null?draftInterval:interval.getSelectedItemPosition()];}
 private int currentCountdown(){return new int[]{30,60,10}[countdown==null?draftCountdown:countdown.getSelectedItemPosition()];}
 private void beginCapture(){
  if(CaptureService.instance==null){message("先にユーザー補助の権限をオンにしてください。");return;}
  if(CaptureService.running){message("撮影中です。");return;}
  String name=title.getText().toString().trim();if(name.isEmpty()){title.setError("本の名前を入力してください");title.requestFocus();return;}
  saveDraft();long id=db.createBook(name);launchCapture(id,false,currentVertical(),currentRight(),currentWait());
 }
 private void resumeCapture(BookStore.Book book){
  if(CaptureService.instance==null){message("先にユーザー補助の権限をオンにしてください。");return;}
  if(CaptureService.running){message("撮影中の本を先に一時停止してください。");return;}
  SharedPreferences prefs=Store.prefs(this);boolean saved=prefs.contains("captureVertical."+book.id);
  AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("「"+book.title+"」の撮影を再開")
   .setMessage("Kindleで保存済みの最後の画面、またはその次の画面を開いてください。同じ画面は読み飛ばします。")
   .setNegativeButton("戻る",null);
  if(saved){dialog.setPositiveButton("前回の設定で再開",(d,w)->launchCapture(book.id,true,prefs.getBoolean("captureVertical."+book.id,true),prefs.getBoolean("captureRight."+book.id,true),prefs.getInt("captureWait."+book.id,1000)));
   dialog.setNeutralButton("現在の設定で再開",(d,w)->launchCapture(book.id,true,currentVertical(),currentRight(),currentWait()));}
  else dialog.setPositiveButton("再開",(d,w)->launchCapture(book.id,true,currentVertical(),currentRight(),currentWait()));dialog.show();
 }
 private void launchCapture(long id,boolean resume,boolean vertical,boolean right,int wait){
  CaptureService service=CaptureService.instance;
  if(service==null||CaptureService.running){db.markPartial(id);render();message("撮影を開始できませんでした。ユーザー補助の権限を確認してください。");return;}
  service.begin(id,vertical,right,currentCountdown(),wait,resume);
  if(!CaptureService.running){db.markPartial(id);render();message("撮影を開始できませんでした。状態欄を確認してください。");return;}
  Store.prefs(this).edit().putBoolean("captureVertical."+id,vertical).putBoolean("captureRight."+id,right).putInt("captureWait."+id,wait).apply();
  Intent kindle=getPackageManager().getLaunchIntentForPackage("com.amazon.kindlefs");if(kindle==null)kindle=getPackageManager().getLaunchIntentForPackage("com.amazon.kindle");
  if(kindle!=null){try{startActivity(kindle);}catch(Exception e){message("Kindleを手動で開いてください。");}}else message("準備時間中にKindleを手動で開いてください。");
  refreshStatus();
 }
 private void refreshStatus(){
  if(status!=null){String value=Store.prefs(this).getString("status","");
   boolean useful=value!=null&&!value.isEmpty()&&!"本を選ぶか、新しく撮影してください。".equals(value);
   status.setVisibility(useful?View.VISIBLE:View.GONE);if(useful)status.setText(value);}
  if(access!=null)access.setText(CaptureService.instance==null?"撮影権限：未設定":"撮影権限：許可済み");
  if(destination!=null)destination.setText(Store.prefs(this).getString("tree","").isEmpty()?"書き出し先：未選択":"書き出し先：選択済み");
  if(primaryAction!=null&&captureSelected)primaryAction.setText(CaptureService.running?"撮影を一時停止":"撮影を開始");
 }
 private void rename(BookStore.Book book){EditText input=new EditText(this);input.setSingleLine(true);input.setText(book.title);
  new AlertDialog.Builder(this).setTitle("本の名前").setView(input).setPositiveButton("保存",(d,w)->{try{db.renameBook(book.id,input.getText().toString());render();}catch(Exception e){message(e.getMessage());}}).setNegativeButton("戻る",null).show();}
 private void openReader(long bookId,int chapterStart){Intent i=new Intent(this,ReaderActivity.class);i.putExtra("bookId",bookId);if(chapterStart>=0)i.putExtra("chapterStart",chapterStart);startActivity(i);}
 private void chooseChapter(long bookId){List<BookStore.Chapter> chapters=db.listChapters(bookId);if(chapters.isEmpty()){message("章がありません。");return;}
  String[] names=new String[chapters.size()];for(int i=0;i<names.length;i++)names[i]=chapters.get(i).title;
  new AlertDialog.Builder(this).setTitle("読み始める章").setItems(names,(d,which)->openReader(bookId,chapters.get(which).startSeq)).setNegativeButton("戻る",null).show();}
 private void pickFolder(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,10);}
 private void pickEpub(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/epub+zip");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,11);}
 private void exportBook(long bookId){String tree=Store.prefs(this).getString("tree","");if(tree.isEmpty()){pendingExportBook=bookId;pickFolder();return;}
  if(status!=null)status.setText("EPUBと本文PDFを書き出しています…");Uri target=Uri.parse(tree);
  new Thread(()->{String result;try(BookStore workerDb=new BookStore(this)){String epub=BookArchive.exportEpub(this,workerDb,bookId,target);String pdf=BookArchive.exportPdf(this,workerDb,bookId,target);result="書き出しました：\n"+epub+"\n"+pdf;}
   catch(Exception e){result="書き出しエラー: "+e.getMessage();}final String output=result;runOnUiThread(()->{Store.status(this,output);refreshStatus();message(output);});},"shiori-export").start();
 }
 private String displayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "取り込んだ本.epub";}
 private void importEpub(Uri uri){String name=displayName(uri);if(status!=null)status.setText("EPUBを取り込んでいます…");
  new Thread(()->{String result;try(InputStream input=getContentResolver().openInputStream(uri);BookStore workerDb=new BookStore(this)){if(input==null)throw new IllegalStateException("ファイルを開けません");BookArchive.Imported imported=BookArchive.importOldEpub(input,name);workerDb.importBook(imported);result="「"+imported.title+"」を本棚に追加しました。";}
   catch(Exception e){result="取り込みエラー: "+e.getMessage();}final String output=result;runOnUiThread(()->{Store.status(this,output);render();message(output);});},"shiori-import").start();
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
  if(request==10){try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));Store.prefs(this).edit().putString("tree",uri.toString()).apply();refreshStatus();if(pendingExportBook>0){long id=pendingExportBook;pendingExportBook=-1;exportBook(id);}}
   catch(Exception e){message("このフォルダの保存権限を取得できませんでした。");}}
  else if(request==11)importEpub(uri);
 }
 @Override protected void onResume(){super.onResume();render();handler.removeCallbacks(refresh);handler.post(refresh);}
 @Override protected void onPause(){saveDraft();handler.removeCallbacks(refresh);super.onPause();}
 @Override protected void onDestroy(){handler.removeCallbacks(refresh);if(db!=null)db.close();super.onDestroy();}
}
