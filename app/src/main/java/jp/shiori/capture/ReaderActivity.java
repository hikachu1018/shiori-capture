package jp.shiori.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Offline RSVP reader. A chapter boundary pauses playback until the user selects the next chapter. */
public final class ReaderActivity extends Activity {
 private static final class Phrase{
  final int seq,offset;final String text;
  Phrase(int seq,int offset,String text){this.seq=seq;this.offset=offset;this.text=text;}
 }
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final List<Phrase> phrases=new ArrayList<>();
 private List<BookStore.Chapter> chapters;
 private BookStore db;private long bookId;private int position,speed;private boolean playing;
 private TextView title,chapter,word,progress,speedLabel;private Button playButton;
 private final Runnable tick=new Runnable(){@Override public void run(){if(!playing)return;if(position+1>=phrases.size()||chapterAt(phrases.get(position+1).seq)!=chapterAt(phrases.get(position).seq)){pause();return;}position++;showPhrase();handler.postDelayed(this,Math.max(100,60000/speed));}};
 private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 private TextView label(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextColor(0xff173249);v.setTextSize(size);v.setPadding(dp(8),dp(10),dp(8),dp(10));return v;}
 private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setOnClickListener(v->action.run());b.setMinHeight(dp(52));return b;}
 private void row(LinearLayout parent,Button... buttons){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);for(Button b:buttons)r.addView(b,new LinearLayout.LayoutParams(0,dp(56),1));parent.addView(r);}
 @Override public void onCreate(Bundle state){super.onCreate(state);
  bookId=getIntent().getLongExtra("bookId",-1);db=new BookStore(this);BookStore.Book book=db.getBook(bookId);
  if(book==null){finish();return;}
  chapters=db.listChapters(bookId);speed=Math.max(80,Math.min(600,Store.prefs(this).getInt("rsvpSpeed",240)));
  for(BookStore.Paragraph p:db.listParagraphs(bookId))for(PhraseTokenizer.Token token:PhraseTokenizer.segment(p.text))phrases.add(new Phrase(p.seq,token.offset,token.text));
  int requested=getIntent().getIntExtra("chapterStart",-1);
  if(requested>=0)position=firstAt(requested);
  else{position=Math.max(0,phrases.size()-1);for(int i=0;i<phrases.size();i++){Phrase p=phrases.get(i);if(p.seq>book.readSeq||(p.seq==book.readSeq&&p.offset>=book.readOffset)){position=i;break;}}}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(24),dp(16),dp(16));root.setBackgroundColor(0xfff3f6f8);setContentView(root);
  root.setOnApplyWindowInsetsListener((v,in)->{Insets i=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(16)+i.left,dp(24)+i.top,dp(16)+i.right,dp(16)+i.bottom);return in;});
  title=label(book.title,22);root.addView(title);
  chapter=label("",16);root.addView(chapter);
  word=label("",42);word.setGravity(Gravity.CENTER);word.setBackgroundColor(0xffffffff);root.addView(word,new LinearLayout.LayoutParams(-1,0,1));
  progress=label("",14);root.addView(progress);
  playButton=button("再生",()->{if(playing)pause();else play();});
  row(root,button("戻る",()->step(-1)),playButton,button("進む",()->step(1)));
  speedLabel=label("",15);speedLabel.setGravity(Gravity.CENTER);root.addView(speedLabel);
  row(root,button("遅く",()->setSpeed(speed-20)),button("速く",()->setSpeed(speed+20)));
  row(root,button("章を選ぶ",this::chooseChapter),button("前の章",()->jumpChapter(-1)),button("次の章",()->jumpChapter(1)));
  showPhrase();
 }
 private int firstAt(int seq){for(int i=0;i<phrases.size();i++)if(phrases.get(i).seq>=seq)return i;return Math.max(0,phrases.size()-1);}
 private int chapterAt(int seq){int index=0;for(int i=0;i<chapters.size();i++)if(chapters.get(i).startSeq<=seq)index=i;else break;return index;}
 private void showPhrase(){
  if(phrases.isEmpty()){word.setText("本文がありません");chapter.setText("");progress.setText("");playButton.setEnabled(false);return;}
  Phrase current=phrases.get(position);int ch=chapterAt(current.seq);
  chapter.setText(chapters.isEmpty()?"冒頭":chapters.get(ch).title);word.setText(current.text);
  progress.setText((position+1)+" / "+phrases.size()+"  ·  "+(ch+1)+" / "+Math.max(1,chapters.size())+"章");
  speedLabel.setText(speed+" 文節/分");db.saveProgress(bookId,current.seq,current.offset);
 }
 private void setSpeed(int value){speed=Math.max(80,Math.min(600,value));Store.prefs(this).edit().putInt("rsvpSpeed",speed).apply();showPhrase();if(playing){handler.removeCallbacks(tick);handler.postDelayed(tick,Math.max(100,60000/speed));}}
 private void step(int delta){pause();if(phrases.isEmpty())return;position=Math.max(0,Math.min(phrases.size()-1,position+delta));showPhrase();}
 private void play(){if(phrases.isEmpty())return;playing=true;playButton.setText("一時停止");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);handler.postDelayed(tick,Math.max(100,60000/speed));}
 private void pause(){playing=false;handler.removeCallbacks(tick);if(playButton!=null)playButton.setText("再生");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
 private void chooseChapter(){
  if(chapters.isEmpty())return;
  String[] names=new String[chapters.size()];for(int i=0;i<names.length;i++)names[i]=chapters.get(i).title;
  new AlertDialog.Builder(this).setTitle("章を選ぶ").setItems(names,(dialog,which)->{pause();position=firstAt(chapters.get(which).startSeq);showPhrase();}).setNegativeButton("戻る",null).show();
 }
 private void jumpChapter(int delta){if(phrases.isEmpty()||chapters.isEmpty())return;int target=chapterAt(phrases.get(position).seq)+delta;if(target<0||target>=chapters.size())return;pause();position=firstAt(chapters.get(target).startSeq);showPhrase();}
 @Override protected void onPause(){pause();super.onPause();}
 @Override protected void onDestroy(){handler.removeCallbacks(tick);if(db!=null)db.close();super.onDestroy();}
}
