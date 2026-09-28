package jp.shiori.capture;
import android.accessibilityservice.*;
import android.view.accessibility.*;
import android.content.*;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

public class CaptureService extends AccessibilityService {
 public static volatile CaptureService instance;
 public static volatile boolean running;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private boolean working=false,finishing=false,cancel=false; private int runId,captured,delay,retries,width,height,emptyCount,pending;
 private boolean vertical,right;private String stopReason="";private long previousHash;private boolean hasHash;
 private int resumeFirstScreen=-1;private String resumeLastText="";private Long resumeLastHash;
 private LinearLayout overlay;private TextView overlayText;private WindowManager wm;
 private PowerManager.WakeLock wake;
 private TextRecognizer recognizer;private BookStore books;private long bookId;
 @Override protected void onServiceConnected(){instance=this;Store.status(this,"権限が有効です。本の名前を入力して撮影を開始できます。");}
 @Override public void onAccessibilityEvent(AccessibilityEvent e){}
 @Override public void onInterrupt(){requestStop("権限サービスが中断されました。");}
 @Override public void onDestroy(){requestStop("サービスが終了しました。保存済みの本文を本棚で確認してください。");instance=null;hideOverlay();super.onDestroy();}
 private boolean isKindle(){AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;CharSequence p=root.getPackageName();String s=p==null?"":p.toString();return s.equals("com.amazon.kindlefs")||s.equals("com.amazon.kindle");}
 public void begin(long id,boolean v,boolean r,int seconds,int wait,boolean resume){
  if(running)return;
  bookId=id;vertical=v;right=r;delay=wait;captured=0;emptyCount=0;pending=0;cancel=false;working=false;finishing=false;stopReason="";hasHash=false;retries=0;width=0;height=0;resumeFirstScreen=-1;resumeLastText="";resumeLastHash=null;int run=++runId;
  try{books=new BookStore(this);
   if(resume){BookStore.CaptureCheckpoint checkpoint=books.resumeCapture(bookId);captured=checkpoint.lastScreen;resumeFirstScreen=captured+1;resumeLastText=checkpoint.lastText;resumeLastHash=checkpoint.lastHash;}
   recognizer=TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
   PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"shiori:capture");wake.acquire(3*60*60*1000L);
   running=true;Store.prefs(this).edit().putBoolean("running",true).putLong("runningBookId",bookId).apply();showOverlay();countdown(run,seconds);
  }catch(Exception e){running=false;cleanup();Store.status(this,"開始できません: "+e.getMessage());}
 }
 private void countdown(int id,int seconds){if(id!=runId||!running||finishing)return;if(cancel){finish();return;}if(seconds<=0){capture(id);return;}status("開始まで "+seconds+"秒 · Kindleを全画面で開いてください");main.postDelayed(()->countdown(id,seconds-1),1000);}
 private void capture(int id){
  if(id!=runId||!running||finishing)return;if(cancel){finish();return;}
  if(!isKindle()){requestStop("Kindleが前面にないため停止しました。");return;}
  if(pending>=3){main.postDelayed(()->capture(id),200);return;}
  status("撮影 "+(captured+1)+"画面目");working=true;
  if(overlay!=null)overlay.setVisibility(View.GONE);
  main.postDelayed(()->{
   if(cancel){working=false;finish();return;}
   if(!isKindle()){working=false;requestStop("画面が切り替わったため停止しました。");return;}
   try{takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
    public void onSuccess(ScreenshotResult result){
     HardwareBuffer buffer=result.getHardwareBuffer();Bitmap hw=null,bitmap=null;
     try{if(buffer==null)throw new Exception("画像バッファがありません");hw=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hw==null)throw new Exception("画像を読み取れません");bitmap=hw.copy(Bitmap.Config.ARGB_8888,false);}
     catch(Exception e){working=false;requestStop("画像取得に失敗しました: "+e.getMessage());return;}
     finally{if(hw!=null)hw.recycle();if(buffer!=null)buffer.close();}
     if(overlay!=null)overlay.setVisibility(View.VISIBLE);
     if(cancel||!isKindle()){bitmap.recycle();working=false;requestStop("撮影中に画面が切り替わったため停止しました。");return;}
     if(width!=0&&(width!=bitmap.getWidth()||height!=bitmap.getHeight())){bitmap.recycle();working=false;requestStop("画面の向きが変わったため停止しました。縦向きに固定して再実行してください。");return;}
     width=bitmap.getWidth();height=bitmap.getHeight();
     long hash=fingerprint(bitmap);
     if(captured+1==resumeFirstScreen&&resumeLastHash!=null&&resumeLastHash==hash){
      bitmap.recycle();working=false;hasHash=true;previousHash=hash;status("前回保存した画面を読み飛ばして再開しました");advance(id);return;
     }
     if(hasHash&&hash==previousHash){
      bitmap.recycle();working=false;
      if(++retries>=2)requestStop("同じ画面が続いたため停止しました。本の末尾を確認してください。");
      else{status("末尾を確認中…");main.postDelayed(()->capture(id),700);}
      return;
     }
     retries=0;hasHash=true;previousHash=hash;
     int screen=++captured;pending++;Bitmap ready=bitmap;
     worker.execute(()->process(id,ready,screen,hash));
     working=false;
     if(screen>=1000){requestStop("安全上限の1000画面に達したため停止しました。");return;}
     if(screen!=resumeFirstScreen)advance(id);
    }
    public void onFailure(int code){working=false;if(overlay!=null)overlay.setVisibility(View.VISIBLE);requestStop("画面を撮影できませんでした（コード "+code+"）。画面ロック・撮影制限・権限を確認してください。");}
   });}catch(Exception e){working=false;requestStop("撮影を開始できません: "+e.getMessage());}
  },250);
 }
 private void process(int id,Bitmap bitmap,int screen,long hash){
  String problem=null;boolean empty=false,duplicate=false;
  try{
   Bitmap ocrBitmap=prepareOcr(bitmap);
   double ink=inkFraction(ocrBitmap);
   Text result;try{result=Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBitmap,0)),90,TimeUnit.SECONDS);}finally{if(ocrBitmap!=bitmap)ocrBitmap.recycle();}
   String text=KindleProgressFilter.cleanText(readingText(result,vertical,bitmap.getHeight()));
   empty=text.trim().isEmpty();
   duplicate=screen==resumeFirstScreen&&CaptureResume.sameText(resumeLastText,text);
   if(!duplicate){
    String imageKind=CapturePageClassifier.imageKind(screen,text,ink);
    byte[] image=imageKind==null?null:encodeImage(bitmap);
    books.appendScreen(bookId,screen,text,hash,imageKind,image,CapturePageClassifier.title(imageKind,text));
   }
  }catch(Exception e){problem="文字認識・保存で停止: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
  finally{bitmap.recycle();}
  final String error=problem;final boolean wasEmpty=empty,wasDuplicate=duplicate;
  main.post(()->{pending--;if(wasEmpty&&!wasDuplicate)emptyCount++;if(id!=runId||!running)return;if(wasDuplicate){captured--;status("前回保存した画面を読み飛ばして再開しました");}if(error!=null)requestStop(error);else if(screen==resumeFirstScreen&&!cancel)advance(id);});
 }
 private void advance(int id){
  if(cancel||finishing||id!=runId)return;
  if(!isKindle()){requestStop("Kindle以外へ切り替わったため停止しました。");return;}
  Path path=new Path();path.moveTo(width*(right?.20f:.80f),height*.55f);path.lineTo(width*(right?.80f:.20f),height*.55f);
  GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,240)).build();
  boolean dispatched=dispatchGesture(gesture,new GestureResultCallback(){
   @Override public void onCompleted(GestureDescription g){if(running&&!finishing&&!cancel)main.postDelayed(()->capture(id),delay);}
   @Override public void onCancelled(GestureDescription g){requestStop("ページ送りが中断されました。");}
  },main);
  if(!dispatched)requestStop("ページ送りを実行できませんでした。ユーザー補助の権限を確認してください。");
 }
 static Bitmap prepareOcr(Bitmap source){
  long light=0;int n=0;for(int y=source.getHeight()/10;y<source.getHeight();y+=Math.max(1,source.getHeight()/10))for(int x=source.getWidth()/10;x<source.getWidth();x+=Math.max(1,source.getWidth()/10)){int p=source.getPixel(x,y);light+=(Color.red(p)+Color.green(p)+Color.blue(p))/3;n++;}
  if(n==0||light/n>=100)return source;
  Bitmap result=Bitmap.createBitmap(source.getWidth(),source.getHeight(),Bitmap.Config.ARGB_8888);Paint paint=new Paint();paint.setColorFilter(new ColorMatrixColorFilter(new float[]{-1,0,0,0,255,0,-1,0,0,255,0,0,-1,0,255,0,0,0,1,0}));new Canvas(result).drawBitmap(source,0,0,paint);return result;
 }
 static double inkFraction(Bitmap bitmap){
  int dark=0,total=0,w=bitmap.getWidth(),h=bitmap.getHeight();
  for(int y=0;y<64;y++)for(int x=0;x<64;x++){
   int pixel=bitmap.getPixel(Math.min(w-1,(int)(w*(.08+(x+.5)*.84/64))),Math.min(h-1,(int)(h*(.07+(y+.5)*.83/64))));
   int light=(Color.red(pixel)*3+Color.green(pixel)*6+Color.blue(pixel))/10;
   if(light<225)dark++;total++;
  }
  return total==0?0:(double)dark/total;
 }
 static byte[] encodeImage(Bitmap source){
  int max=Math.max(source.getWidth(),source.getHeight());
  Bitmap scaled=max>1280?Bitmap.createScaledBitmap(source,Math.max(1,source.getWidth()*1280/max),Math.max(1,source.getHeight()*1280/max),true):source;
  try{
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   if(!scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY,80,out))throw new IllegalStateException("画像を圧縮できません");
   return out.toByteArray();
  }finally{if(scaled!=source)scaled.recycle();}
 }
 static long fingerprint(Bitmap b){
  // Sample the central page; omit the status bar, navigation bar and moving page indicator.
  long hash=0xcbf29ce484222325L;
  int w=b.getWidth(),h=b.getHeight();
  for(int row=0;row<48;row++)for(int col=0;col<32;col++){
   int p=b.getPixel((int)(w*(.08+(col+.5)*.84/32)),(int)(h*(.10+(row+.5)*.78/48)));
   int gray=(Color.red(p)*3+Color.green(p)*6+Color.blue(p))/10;
   hash=(hash^(gray/16))*0x100000001b3L;
  }
  return hash;
 }
 static String readingText(Text recognized,boolean vertical,int screenHeight){
  List<Text.Line> lines=new ArrayList<>();for(Text.TextBlock block:recognized.getTextBlocks())for(Text.Line line:block.getLines()){
   Rect bounds=line.getBoundingBox();
   // Kindle places the chapter progress below the reading area; ignore it even if OCR garbles the label.
   if(bounds==null||!KindleProgressFilter.isFooterRegion(bounds.top,screenHeight))lines.add(line);
  }
  // Stable column grouping for Japanese vertical layout; broad illustrations and ruby may still require review.
  if(vertical){lines.sort(Comparator.comparingInt((Text.Line l)->l.getBoundingBox()==null?0:l.getBoundingBox().right).reversed());}
  else {lines.sort(Comparator.comparingInt((Text.Line l)->l.getBoundingBox()==null?0:l.getBoundingBox().top).thenComparingInt(l->l.getBoundingBox()==null?0:l.getBoundingBox().left));}
  if(vertical){List<Text.Line> ordered=new ArrayList<>();while(!lines.isEmpty()){Text.Line first=lines.remove(0);Rect box=first.getBoundingBox();List<Text.Line> col=new ArrayList<>();col.add(first);if(box!=null){float x=box.centerX();float tolerance=Math.max(8,box.width()*.75f);Iterator<Text.Line> it=lines.iterator();while(it.hasNext()){Text.Line l=it.next();Rect r=l.getBoundingBox();if(r!=null&&Math.abs(r.centerX()-x)<tolerance){col.add(l);it.remove();}}}col.sort(Comparator.comparingInt(l->l.getBoundingBox()==null?0:l.getBoundingBox().top));ordered.addAll(col);}lines=ordered;}
  StringBuilder out=new StringBuilder();for(Text.Line line:lines){if(out.length()>0)out.append('\n');out.append(line.getText());}return out.toString();
 }
 public void requestStop(String why){if(!running||finishing)return;if(cancel){if(!working)finish();return;}cancel=true;stopReason=why;status(why+" 保存中…");if(!working)finish();}
 private void finish(){if(finishing||!running)return;finishing=true;hideOverlay();worker.execute(()->{
  String error=null;int saved=0;try{books.finishCapture(bookId,stopReason.contains("同じ画面"));saved=books.countScreens(bookId);}catch(Exception e){error=e.getMessage();}
  final String err=error;final int savedScreens=saved;main.post(()->{
   String s=stopReason+"\n";
   if(err!=null)s+="本棚への保存エラー: "+err;
   else if(savedScreens==0)s+="本文はありません。";
   else s+="合計"+savedScreens+"画面の本文を本棚に保存しました。章を確認して読書できます。";
   if(emptyCount>0)s+="\n文字を認識できない画面が"+emptyCount+"枚あります。";
   Store.status(this,s);running=false;finishing=false;working=false;
   Store.prefs(this).edit().putBoolean("running",false).remove("runningBookId").apply();cleanup();
  });
 });}
 private void cleanup(){hideOverlay();if(wake!=null&&wake.isHeld())wake.release();wake=null;if(recognizer!=null){recognizer.close();recognizer=null;}if(books!=null){books.close();books=null;}}
 private void status(String text){Store.status(this,text);if(overlayText!=null)overlayText.setText(text);}
 private void showOverlay(){
  wm=(WindowManager)getSystemService(WINDOW_SERVICE);
  overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.HORIZONTAL);overlay.setGravity(Gravity.CENTER_VERTICAL);
  overlay.setPadding(Ui.dp(this,14),Ui.dp(this,7),Ui.dp(this,8),Ui.dp(this,7));
  overlay.setBackground(Ui.background(this,0xf518313a,16,0));
  overlayText=new TextView(this);overlayText.setTextColor(Color.WHITE);overlayText.setTextSize(13);overlayText.setMaxLines(2);
  overlay.addView(overlayText,new LinearLayout.LayoutParams(0,-2,1));
  Button stop=Ui.button(this,"一時停止",Ui.PRIMARY,()->requestStop("一時停止しました。"));
  overlay.addView(stop,new LinearLayout.LayoutParams(Ui.dp(this,112),Ui.dp(this,48)));
  WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,PixelFormat.TRANSLUCENT);
  lp.gravity=Gravity.TOP;lp.x=Ui.dp(this,8);lp.y=Ui.dp(this,8);wm.addView(overlay,lp);
 }
 private void hideOverlay(){if(overlay!=null&&wm!=null){try{wm.removeView(overlay);}catch(Exception ignored){}overlay=null;overlayText=null;}}
}
