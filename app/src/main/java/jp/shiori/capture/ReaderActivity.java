package jp.shiori.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** An offline, focused RSVP reader that stops at chapter boundaries. */
public final class ReaderActivity extends Activity {
 private static final class Phrase{
  final int seq,offset;final String text;
  Phrase(int seq,int offset,String text){this.seq=seq;this.offset=offset;this.text=text;}
 }
 private final Handler handler=new Handler(Looper.getMainLooper());private final List<Phrase> phrases=new ArrayList<>();
 private List<BookStore.Chapter> chapters;private BookStore db;private long bookId;
 private int position,speed;private boolean playing;
 private TextView word,progress,speedLabel,hint;private Button playButton,chapterButton;private ProgressBar progressBar;
 private final Runnable tick=new Runnable(){@Override public void run(){
  if(!playing)return;
  if(position+1>=phrases.size()||chapterAt(phrases.get(position+1).seq)!=chapterAt(phrases.get(position).seq)){
   pause();updateHint();return;
  }
  position++;showPhrase();handler.postDelayed(this,Math.max(100,60000/speed));
 }};
 private int dp(int n){return Ui.dp(this,n);}
 private LinearLayout row(){LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.HORIZONTAL);return result;}
 private void addRowButton(LinearLayout row,Button button,int weight,int rightMargin){
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),weight);p.rightMargin=dp(rightMargin);row.addView(button,p);
 }
 @Override public void onCreate(Bundle state){super.onCreate(state);
  bookId=getIntent().getLongExtra("bookId",-1);db=new BookStore(this);BookStore.Book book=db.getBook(bookId);if(book==null){finish();return;}
  chapters=db.listChapters(bookId);speed=Math.max(80,Math.min(600,Store.prefs(this).getInt("rsvpSpeed",240)));
  for(BookStore.Paragraph p:db.listParagraphs(bookId))for(PhraseTokenizer.Token token:PhraseTokenizer.segment(p.text))phrases.add(new Phrase(p.seq,token.offset,token.text));
  int requested=getIntent().getIntExtra("chapterStart",-1);
  if(requested>=0)position=firstAt(requested);
  else{position=Math.max(0,phrases.size()-1);for(int i=0;i<phrases.size();i++){Phrase p=phrases.get(i);if(p.seq>book.readSeq||(p.seq==book.readSeq&&p.offset>=book.readOffset)){position=i;break;}}}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BACKGROUND);root.setPadding(dp(18),dp(15),dp(18),dp(16));setContentView(root);
  root.setOnApplyWindowInsetsListener((v,in)->{Insets i=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(18)+i.left,dp(15)+i.top,dp(18)+i.right,dp(16)+i.bottom);return in;});
  LinearLayout top=row();Button back=Ui.button(this,"本棚へ",Ui.PLAIN,this::finish);addRowButton(top,back,1,10);
  TextView title=Ui.text(this,book.title,19,Ui.INK,true);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setGravity(Gravity.CENTER_VERTICAL);
  top.addView(title,new LinearLayout.LayoutParams(0,dp(52),3));root.addView(top);
  chapterButton=Ui.button(this,"章を選ぶ",Ui.SECONDARY,this::chooseChapter);root.addView(chapterButton,Ui.margins(this,7,0));
  LinearLayout focus=Ui.card(this);focus.setPadding(dp(12),dp(20),dp(12),dp(12));
  LinearLayout.LayoutParams focusParams=new LinearLayout.LayoutParams(-1,0,1);focusParams.topMargin=dp(13);root.addView(focus,focusParams);
  word=Ui.text(this,"",46,Ui.INK,true);word.setGravity(Gravity.CENTER);word.setMaxLines(3);
  word.setAutoSizeTextTypeUniformWithConfiguration(26,48,2,TypedValue.COMPLEX_UNIT_SP);
  focus.addView(word,new LinearLayout.LayoutParams(-1,0,1));
  hint=Ui.text(this,"",13,Ui.MUTED,false);hint.setGravity(Gravity.CENTER);focus.addView(hint,new LinearLayout.LayoutParams(-1,dp(28)));
  progress=Ui.text(this,"",13,Ui.MUTED,false);root.addView(progress,Ui.margins(this,13,4));
  progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
  progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.BORDER));root.addView(progressBar,new LinearLayout.LayoutParams(-1,dp(5)));
  LinearLayout controls=row();Button previous=Ui.button(this,"戻る",Ui.SECONDARY,()->step(-1));playButton=Ui.button(this,"再生",Ui.PRIMARY,()->{if(playing)pause();else play();});
  Button next=Ui.button(this,"進む",Ui.SECONDARY,()->step(1));addRowButton(controls,previous,1,8);addRowButton(controls,playButton,2,8);addRowButton(controls,next,1,0);root.addView(controls,Ui.margins(this,16,0));
  LinearLayout speedRow=row();speedLabel=Ui.text(this,"",14,Ui.INK,true);speedLabel.setGravity(Gravity.CENTER_VERTICAL);speedRow.addView(speedLabel,new LinearLayout.LayoutParams(0,dp(46),1));
  SeekBar seek=new SeekBar(this);seek.setMax(26);seek.setProgress((speed-80)/20);seek.setContentDescription("読書速度");
  seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser)setSpeed(80+value*20);}
   public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
  speedRow.addView(seek,new LinearLayout.LayoutParams(0,dp(46),2));root.addView(speedRow,Ui.margins(this,12,0));
  LinearLayout chaptersRow=row();Button prevChapter=Ui.button(this,"前の章",Ui.PLAIN,()->jumpChapter(-1));Button nextChapter=Ui.button(this,"次の章",Ui.PLAIN,()->jumpChapter(1));
  addRowButton(chaptersRow,prevChapter,1,8);addRowButton(chaptersRow,nextChapter,1,0);root.addView(chaptersRow,Ui.margins(this,7,0));
  showPhrase();
 }
 private int firstAt(int seq){for(int i=0;i<phrases.size();i++)if(phrases.get(i).seq>=seq)return i;return Math.max(0,phrases.size()-1);}
 private int chapterAt(int seq){int index=0;for(int i=0;i<chapters.size();i++)if(chapters.get(i).startSeq<=seq)index=i;else break;return index;}
 private boolean atBoundary(){return !phrases.isEmpty()&&(position+1>=phrases.size()||chapterAt(phrases.get(position+1).seq)!=chapterAt(phrases.get(position).seq));}
 private void updateHint(){if(hint==null)return;if(phrases.isEmpty())hint.setText("本文がありません");else if(position+1>=phrases.size())hint.setText("本の終わりです");else if(atBoundary())hint.setText("章の終わりです。次の章を選べます");else hint.setText(playing?"再生中 · タップで一時停止":"再生で続きを読みます");}
 private void showPhrase(){
  if(phrases.isEmpty()){word.setText("本文がありません");progress.setText("");progressBar.setProgress(0);playButton.setEnabled(false);updateHint();return;}
  Phrase current=phrases.get(position);int chapterIndex=chapterAt(current.seq);
  String name=chapters.isEmpty()?"冒頭":chapters.get(chapterIndex).title;chapterButton.setText(name+" を選ぶ");word.setText(current.text);
  progress.setText((position+1)+" / "+phrases.size()+" 文節  ·  "+(chapterIndex+1)+" / "+Math.max(1,chapters.size())+" 章");
  progressBar.setMax(phrases.size());progressBar.setProgress(position+1);
  speedLabel.setText("速さ  "+speed+"/分");db.saveProgress(bookId,current.seq,current.offset);updateHint();
 }
 private void setSpeed(int value){speed=Math.max(80,Math.min(600,value));Store.prefs(this).edit().putInt("rsvpSpeed",speed).apply();
  speedLabel.setText("速さ  "+speed+"/分");if(playing){handler.removeCallbacks(tick);handler.postDelayed(tick,Math.max(100,60000/speed));}}
 private void step(int delta){pause();if(phrases.isEmpty())return;position=Math.max(0,Math.min(phrases.size()-1,position+delta));showPhrase();}
 private void play(){if(phrases.isEmpty())return;if(atBoundary()){updateHint();return;}playing=true;playButton.setText("一時停止");
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateHint();handler.postDelayed(tick,Math.max(100,60000/speed));}
 private void pause(){playing=false;handler.removeCallbacks(tick);if(playButton!=null)playButton.setText("再生");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateHint();}
 private void chooseChapter(){if(chapters.isEmpty())return;String[] names=new String[chapters.size()];for(int i=0;i<names.length;i++)names[i]=chapters.get(i).title;
  new AlertDialog.Builder(this).setTitle("読み始める章").setItems(names,(dialog,which)->{pause();position=firstAt(chapters.get(which).startSeq);showPhrase();}).setNegativeButton("戻る",null).show();}
 private void jumpChapter(int delta){if(phrases.isEmpty()||chapters.isEmpty())return;int target=chapterAt(phrases.get(position).seq)+delta;
  if(target<0||target>=chapters.size())return;pause();position=firstAt(chapters.get(target).startSeq);showPhrase();}
 @Override protected void onPause(){pause();super.onPause();}
 @Override protected void onDestroy(){handler.removeCallbacks(tick);if(db!=null)db.close();super.onDestroy();}
}
