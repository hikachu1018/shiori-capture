package jp.shiori.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/** Rename, add, move and merge chapter boundaries at OCR paragraph positions. */
public final class ChapterEditorActivity extends Activity {
 private static final class Group{
  final String name;final List<BookStore.Paragraph> paragraphs=new ArrayList<>();
  Group(String name){this.name=name;}
 }
 private BookStore db;private long bookId;private LinearLayout content;
 private int dp(int n){return Ui.dp(this,n);}
 private TextView text(String value,int size,int color,boolean bold){return Ui.text(this,value,size,color,bold);}
 private void message(String value){new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK",null).show();}
 @Override public void onCreate(Bundle state){super.onCreate(state);bookId=getIntent().getLongExtra("bookId",-1);db=new BookStore(this);if(db.getBook(bookId)==null){finish();return;}refresh();}
 private void refresh(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BACKGROUND);setContentView(root);
  root.setOnApplyWindowInsetsListener((v,in)->{Insets i=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom);return in;});
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(22),dp(20),dp(32));scroll.addView(content);
  BookStore.Book book=db.getBook(bookId);
  Button back=Ui.button(this,"本棚へ戻る",Ui.PLAIN,this::finish);content.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));
  content.addView(text("章を編集",27,Ui.INK,true));
  content.addView(text(book.title,15,Ui.MUTED,false),Ui.margins(this,4,0));
  content.addView(text("章名と読み始める段落を確認できます。変更は読書画面にも反映されます。",14,Ui.MUTED,false),Ui.margins(this,14,0));
  Button redetect=Ui.button(this,"目次から章を再検出",Ui.SECONDARY,this::confirmRedetect);content.addView(redetect,Ui.margins(this,12,0));
  Button fromToc=Ui.button(this,"目次の項目から章を追加",Ui.PLAIN,this::chooseTocTitle);content.addView(fromToc,Ui.margins(this,8,0));
  List<BookStore.Chapter> chapters=db.listChapters(bookId);
  List<BookStore.Paragraph> paragraphs=db.listParagraphs(bookId);
  for(int i=0;i<chapters.size();i++)addChapterCard(i,chapters.get(i),paragraphs);
  Button add=Ui.button(this,"章を追加",Ui.PRIMARY,()->chooseBoundary(seq->addChapterAt(seq,suggestTitle(seq))));
  LinearLayout bottom=new LinearLayout(this);bottom.setPadding(dp(20),dp(10),dp(20),dp(12));bottom.setBackground(Ui.background(this,Ui.SURFACE,0,0));
  bottom.addView(add,new LinearLayout.LayoutParams(-1,dp(54)));root.addView(bottom);
 }
 private void addChapterCard(int index,BookStore.Chapter chapter,List<BookStore.Paragraph> paragraphs){
  LinearLayout card=Ui.card(this);content.addView(card,Ui.margins(this,16,0));
  card.addView(text("章 "+(index+1),13,Ui.GREEN,true));
  card.addView(text(chapter.title,19,Ui.INK,true),Ui.margins(this,5,0));
  String excerpt="";for(BookStore.Paragraph p:paragraphs)if(p.seq>=chapter.startSeq){excerpt=shortText(p.text,54);break;}
  card.addView(text("段落 "+(chapter.startSeq+1)+" から · "+excerpt,13,Ui.MUTED,false),Ui.margins(this,8,0));
  Button edit=Ui.button(this,"章名と開始位置を編集",Ui.SECONDARY,()->{});
  edit.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,edit);
   menu.getMenu().add("章名を変更").setOnMenuItemClickListener(item->{askTitle(chapter.title,name->replace(index,new BookStore.Chapter(chapter.startSeq,name)));return true;});
   if(index>0){menu.getMenu().add("開始位置を変更").setOnMenuItemClickListener(item->{chooseBoundary(seq->replace(index,new BookStore.Chapter(seq,chapter.title)));return true;});
    menu.getMenu().add("前の章と統合").setOnMenuItemClickListener(item->{new AlertDialog.Builder(this).setMessage("「"+chapter.title+"」を前の章に統合しますか？")
     .setPositiveButton("統合",(d,w)->{List<BookStore.Chapter> all=db.listChapters(bookId);all.remove(index);save(all);}).setNegativeButton("戻る",null).show();return true;});}
   menu.show();});
  card.addView(edit,Ui.margins(this,14,0));
 }
 private interface TitleAction{void apply(String title);}
 private void chooseTocTitle(){
  ChapterDetector.Analysis analysis=ChapterDetector.analyze(db.listAllParagraphs(bookId));
  if(analysis.tocTitles.isEmpty()){message("撮影した本文から目次の項目を見つけられませんでした。");return;}
  String[] titles=analysis.tocTitles.toArray(new String[0]);
  new AlertDialog.Builder(this).setTitle("目次の章名を選ぶ").setItems(titles,(dialog,which)->chooseBoundary(seq->addChapterAt(seq,titles[which]))).setNegativeButton("戻る",null).show();
 }
 private String suggestTitle(int seq){
  for(BookStore.Paragraph p:db.listParagraphs(bookId))if(p.seq==seq){
   String candidate=p.text.trim();if(candidate.length()>=2&&candidate.length()<=40&&!candidate.matches(".*[。！？!?]$"))return candidate;
   break;
  }
  return "章 "+(db.listChapters(bookId).size()+1);
 }
 private void addChapterAt(int seq,String name){
  List<BookStore.Chapter> all=db.listChapters(bookId);List<BookStore.Paragraph> visible=db.listParagraphs(bookId);
  int first=visible.isEmpty()?-1:visible.get(0).seq;
  if(seq==first&&all.size()==1&&"冒頭".equals(all.get(0).title)){all.set(0,new BookStore.Chapter(0,name));if(save(all))message("最初の章名を設定しました。必要なら変更できます。");return;}
  for(BookStore.Chapter chapter:all)if(chapter.startSeq==seq){message("その位置には既に章があります。別の画面か段落を選んでください。");return;}
  all.add(new BookStore.Chapter(seq,name));if(save(all))message("章を追加しました。必要なら章名を変更できます。");
 }
 private void confirmRedetect(){
  new AlertDialog.Builder(this).setTitle("章を再検出しますか？")
   .setMessage("撮影した目次と本文の見出しを照合します。手動で編集した章立ては一致した目次の章立てに置き換わり、目次と表紙の画面は読書本文から除外されます。")
   .setNegativeButton("戻る",null).setPositiveButton("再検出",(dialog,which)->new Thread(()->{
    String result;boolean updated=false;
    try(BookStore worker=new BookStore(this)){
     ChapterDetector.Analysis analysis=worker.rebuildChapters(bookId,true);
     if(analysis==null)result="この本に再検出できる撮影本文がありません。";
     else if(analysis.tocCount>0&&analysis.matchedCount==0)result="目次を読書本文から除外しました。本文の章冒頭と一致しなかったため、章立ては変更していません。";
     else {result=analysis.tocCount>0?"章立てを更新しました。目次"+analysis.tocCount+"項目のうち本文で"+analysis.matchedCount+"章を確認しました。":"目次を検出できなかったため、本文の見出しから章立てを更新しました。";updated=true;}
    }catch(Exception e){result="再検出できませんでした: "+e.getMessage();}
    final String output=result;final boolean changed=updated;runOnUiThread(()->{if(changed)refresh();message(output);});
   },"shiori-redetect-chapters").start()).show();
 }
 private void askTitle(String current,TitleAction action){
  EditText input=new EditText(this);input.setSingleLine(true);input.setText(current);input.setSelectAllOnFocus(true);
  new AlertDialog.Builder(this).setTitle("章の名前").setView(input).setPositiveButton("保存",(d,w)->{
   String name=input.getText().toString().trim();if(name.isEmpty()){message("章の名前を入力してください");return;}action.apply(name);
  }).setNegativeButton("戻る",null).show();
 }
 private void replace(int index,BookStore.Chapter replacement){List<BookStore.Chapter> all=db.listChapters(bookId);all.set(index,replacement);save(all);}
 private boolean save(List<BookStore.Chapter> chapters){chapters.sort(Comparator.comparingInt(c->c.startSeq));try{db.editChapters(bookId,chapters);refresh();return true;}
  catch(Exception e){message(e.getMessage()==null?"章を変更できませんでした":e.getMessage());return false;}}
 private void chooseBoundary(IntConsumer selected){
  List<BookStore.Paragraph> paragraphs=db.listParagraphs(bookId);if(paragraphs.size()<2){message("章の境界にできる段落がありません");return;}
  List<Group> groups=new ArrayList<>();String previous="";
  for(int i=0;i<paragraphs.size();i++){BookStore.Paragraph p=paragraphs.get(i);String key=p.screen>0?"画面 "+p.screen:"段落 "+(i/25+1)+"〜";
   if(!key.equals(previous)){groups.add(new Group(key));previous=key;}groups.get(groups.size()-1).paragraphs.add(p);}
  String[] names=new String[groups.size()];for(int i=0;i<groups.size();i++)names[i]=groups.get(i).name+"  "+shortText(groups.get(i).paragraphs.get(0).text,22);
  new AlertDialog.Builder(this).setTitle("開始画面を押すと決定します")
   .setItems(names,(d,which)->{
    Group group=groups.get(which);int first=group.paragraphs.get(0).seq;
    boolean occupied=false;for(BookStore.Chapter ch:db.listChapters(bookId))if(ch.startSeq==first){occupied=true;break;}
    if(occupied&&group.paragraphs.size()>1)chooseParagraph(group,selected);
    else selected.accept(first);
   })
   .setNeutralButton("段落を選ぶ",(d,w)->chooseParagraphScreen(groups,selected))
   .setNegativeButton("戻る",null).show();
 }
 private void chooseParagraphScreen(List<Group> groups,IntConsumer selected){
  String[] names=new String[groups.size()];for(int i=0;i<groups.size();i++)names[i]=groups.get(i).name;
  new AlertDialog.Builder(this).setTitle("段落を選ぶ画面").setItems(names,(d,which)->chooseParagraph(groups.get(which),selected)).setNegativeButton("戻る",null).show();
 }
 private void chooseParagraph(Group group,IntConsumer selected){String[] names=new String[group.paragraphs.size()];
  for(int i=0;i<names.length;i++)names[i]=(group.paragraphs.get(i).seq+1)+": "+shortText(group.paragraphs.get(i).text,65);
  new AlertDialog.Builder(this).setTitle("章の開始段落を選ぶ").setItems(names,(d,which)->selected.accept(group.paragraphs.get(which).seq)).setNegativeButton("戻る",null).show();
 }
 private static String shortText(String value,int limit){return value.length()<=limit?value:value.substring(0,limit)+"…";}
 @Override protected void onDestroy(){if(db!=null)db.close();super.onDestroy();}
}
