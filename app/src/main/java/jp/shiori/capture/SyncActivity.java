package jp.shiori.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pair with the Windows companion by QR or by pasting its connection text. */
public final class SyncActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());private TextView status;private EditText connection;
 private final Runnable refresh=new Runnable(){@Override public void run(){if(status!=null)status.setText(Store.prefs(SyncActivity.this).getString("syncStatus",SyncClient.isPaired(SyncActivity.this)?"PCと接続済み":"未接続"));handler.postDelayed(this,2000);}};
 private void add(LinearLayout parent,String title,Runnable action){parent.addView(Ui.button(this,title,Ui.SECONDARY,action),Ui.margins(this,10,0));}
 @Override public void onCreate(Bundle state){super.onCreate(state);ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Ui.BACKGROUND);setContentView(scroll);
  LinearLayout root=new LinearLayout(this);root.setPadding(Ui.dp(this,22),Ui.dp(this,38),Ui.dp(this,22),Ui.dp(this,24));root.setOrientation(LinearLayout.VERTICAL);scroll.addView(root);
  root.addView(Ui.text(this,"PCと同期",26,Ui.INK,true));root.addView(Ui.text(this,"PC版の『同期の接続情報』を開き、同じWi-Fi上でQRを読み取ってください。両方のアプリが開いている間に自動で同期します。",14,Ui.MUTED,false),Ui.margins(this,12,0));
  status=Ui.text(this,"",15,Ui.GREEN,true);root.addView(status,Ui.margins(this,18,0));
  add(root,"QRコードを読み取る",()->GmsBarcodeScanning.getClient(this).startScan()
   .addOnSuccessListener(barcode->{String value=barcode.getRawValue();if(value!=null){connection.setText(value);pair(value);}})
   .addOnFailureListener(error->new AlertDialog.Builder(this).setMessage("QRを読み取れません: "+error.getMessage()).setPositiveButton("OK",null).show()));
  connection=new EditText(this);connection.setSingleLine(false);connection.setMinLines(3);connection.setHint("PCに表示された接続情報を貼り付ける");root.addView(connection,Ui.margins(this,18,0));
  add(root,"接続情報でペアリング",()->pair(connection.getText().toString()));
  add(root,"今すぐ同期",()->new Thread(()->{try{String result=SyncClient.sync(this);runOnUiThread(()->{Store.prefs(this).edit().putString("syncStatus",result).apply();status.setText(result);});}
   catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setMessage("同期できません: "+e.getMessage()).setPositiveButton("OK",null).show());}},"shiori-sync-now").start());
  add(root,"競合した変更を選ぶ",this::showConflicts);
  add(root,"接続を解除",()->new AlertDialog.Builder(this).setMessage("PCとのペアリングを解除しますか？本棚の本は残ります。")
   .setPositiveButton("解除",(dialog,which)->{Store.prefs(this).edit().remove("syncHost").remove("syncPin").remove("syncToken").apply();status.setText("未接続");}).setNegativeButton("戻る",null).show());
  root.addView(Ui.text(this,"同期できない場合は、PCとGalaxyのWi-Fi、Windowsファイアウォールのプライベートネットワーク許可を確認してください。",13,Ui.MUTED,false),Ui.margins(this,20,0));
 }
 private void pair(String text){new Thread(()->{try{SyncClient.pair(this,text);String result=SyncClient.sync(this);runOnUiThread(()->{status.setText(result);new AlertDialog.Builder(this).setMessage("PCと接続しました。"+result).setPositiveButton("OK",null).show();});}
  catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setMessage("接続できません: "+e.getMessage()).setPositiveButton("OK",null).show());}},"shiori-pair").start();}
 private void showConflicts(){
  List<String> copies=new ArrayList<>();List<String> originals=new ArrayList<>();List<String> labels=new ArrayList<>();
  try(BookStore store=new BookStore(this)){
   Map<String,SyncBookCodec.State> states=SyncBookCodec.states(store);
   for(Map.Entry<String,?> entry:Store.prefs(this).getAll().entrySet()){
    if(!entry.getKey().startsWith("syncConflict:"))continue;
    String copy=entry.getKey().substring("syncConflict:".length()),original=String.valueOf(entry.getValue());
    SyncBookCodec.State copyState=states.get(copy),originalState=states.get(original);
    if(copyState==null||originalState==null||copyState.deletedAt!=0)continue;
    copies.add(copy);originals.add(original);labels.add(store.getBook(originalState.id).title);
   }
  }
  if(copies.isEmpty()){new AlertDialog.Builder(this).setMessage("選択待ちの競合はありません").setPositiveButton("OK",null).show();return;}
  new AlertDialog.Builder(this).setTitle("競合した本を選ぶ").setItems(labels.toArray(new String[0]),
   (dialog,which)->chooseConflict(originals.get(which),copies.get(which))).setNegativeButton("戻る",null).show();
 }
 private static String preview(JSONObject book)throws Exception{
  JSONArray paragraphs=book.getJSONArray("paragraphs");StringBuilder text=new StringBuilder();
  for(int i=0;i<paragraphs.length()&&text.length()<180;i++){
   JSONObject paragraph=paragraphs.getJSONObject(i);if(!paragraph.optBoolean("hidden"))text.append(paragraph.getString("text")).append(' ');
  }
  return text.length()>180?text.substring(0,180)+"…":text.toString();
 }
 private void chooseConflict(String originalUuid,String copyUuid){
  try(BookStore store=new BookStore(this)){
   Map<String,SyncBookCodec.State> states=SyncBookCodec.states(store);
   SyncBookCodec.State original=states.get(originalUuid),copy=states.get(copyUuid);
   if(original==null||copy==null)throw new IllegalStateException("本が見つかりません");
   String pcText=preview(SyncBookCodec.exportBook(store,original.id));
   String galaxyText=preview(SyncBookCodec.exportBook(store,copy.id));
   new AlertDialog.Builder(this).setTitle("残す変更を選ぶ")
    .setMessage("PC版:\n"+pcText+"\n\nGalaxy版:\n"+galaxyText+"\n\n選ばなかった変更も30日間は復元できます。")
    .setPositiveButton("Galaxy版",(dialog,which)->resolveConflict(originalUuid,copyUuid,true))
    .setNegativeButton("PC版",(dialog,which)->resolveConflict(originalUuid,copyUuid,false))
    .setNeutralButton("後で選ぶ",null).show();
  }catch(Exception e){new AlertDialog.Builder(this).setMessage("競合を開けません: "+e.getMessage()).setPositiveButton("OK",null).show();}
 }
 private void resolveConflict(String originalUuid,String copyUuid,boolean keepGalaxy){
  new Thread(()->{try(BookStore store=new BookStore(this)){
   Map<String,SyncBookCodec.State> states=SyncBookCodec.states(store);
   SyncBookCodec.State original=states.get(originalUuid),copy=states.get(copyUuid);
   if(original==null||copy==null)throw new IllegalStateException("本が見つかりません");
   SyncBookCodec.chooseConflict(store,original.id,copy.id,keepGalaxy);
   Store.prefs(this).edit().remove("syncConflict:"+copyUuid).apply();
   runOnUiThread(()->status.setText("選択を保存しました。次の同期でPCにも反映します"));
  }catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setMessage("選択を保存できません: "+e.getMessage()).setPositiveButton("OK",null).show());}},"shiori-conflict").start();
 }
 @Override protected void onResume(){super.onResume();handler.post(refresh);}
 @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
