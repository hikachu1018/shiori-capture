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
  add(root,"接続を解除",()->new AlertDialog.Builder(this).setMessage("PCとのペアリングを解除しますか？本棚の本は残ります。")
   .setPositiveButton("解除",(dialog,which)->{Store.prefs(this).edit().remove("syncHost").remove("syncPin").remove("syncToken").apply();status.setText("未接続");}).setNegativeButton("戻る",null).show());
  root.addView(Ui.text(this,"同期できない場合は、PCとGalaxyのWi-Fi、Windowsファイアウォールのプライベートネットワーク許可を確認してください。",13,Ui.MUTED,false),Ui.margins(this,20,0));
 }
 private void pair(String text){new Thread(()->{try{SyncClient.pair(this,text);String result=SyncClient.sync(this);runOnUiThread(()->{status.setText(result);new AlertDialog.Builder(this).setMessage("PCと接続しました。"+result).setPositiveButton("OK",null).show();});}
  catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setMessage("接続できません: "+e.getMessage()).setPositiveButton("OK",null).show());}},"shiori-pair").start();}
 @Override protected void onResume(){super.onResume();handler.post(refresh);}
 @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
