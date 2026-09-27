package jp.shiori.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextColor(0xff173249);v.setTextSize(size);v.setPadding(0,dp(8),0,dp(8));return v;}
 private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setOnClickListener(v->action.run());b.setMinHeight(dp(48));return b;}
 private void message(String value){new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK",null).show();}
 @Override public void onCreate(Bundle state){super.onCreate(state);bookId=getIntent().getLongExtra("bookId",-1);db=new BookStore(this);if(db.getBook(bookId)==null){finish();return;}refresh();}
 private void refresh(){
  ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xfff3f6f8);
  scroll.setOnApplyWindowInsetsListener((v,in)->{Insets i=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom);return in;});
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(24),dp(20),dp(32));scroll.addView(content);setContentView(scroll);
  BookStore.Book book=db.getBook(bookId);content.addView(text(book.title+" の章",23));
  content.addView(text("章の開始段落を選び直せます。変更は本棚と読書画面にすぐ反映されます。",14));
  content.addView(button("章を追加",()->chooseBoundary(seq->askTitle("新しい章",name->{
   List<BookStore.Chapter> all=db.listChapters(bookId);all.add(new BookStore.Chapter(seq,name));save(all);
  }))));
  List<BookStore.Chapter> chapters=db.listChapters(bookId);
  for(int i=0;i<chapters.size();i++){
   final int index=i;BookStore.Chapter ch=chapters.get(i);
   content.addView(text((i+1)+". "+ch.title+"  （段落 "+(ch.startSeq+1)+"から）",17));
   LinearLayout row=new LinearLayout(this);
   row.addView(button("名前",()->askTitle(ch.title,name->replace(index,new BookStore.Chapter(ch.startSeq,name)))),new LinearLayout.LayoutParams(0,dp(50),1));
   if(i>0){
    row.addView(button("位置",()->chooseBoundary(seq->replace(index,new BookStore.Chapter(seq,ch.title)))),new LinearLayout.LayoutParams(0,dp(50),1));
    row.addView(button("統合",()->new AlertDialog.Builder(this).setMessage("「"+ch.title+"」の境界を削除して前の章にまとめますか？").setPositiveButton("統合",(d,w)->{List<BookStore.Chapter> all=db.listChapters(bookId);all.remove(index);save(all);}).setNegativeButton("戻る",null).show()),new LinearLayout.LayoutParams(0,dp(50),1));
   }
   content.addView(row);
  }
 }
 private interface TitleAction{void apply(String title);}
 private void askTitle(String current,TitleAction action){
  EditText input=new EditText(this);input.setSingleLine(true);input.setText(current);input.setSelectAllOnFocus(true);
  new AlertDialog.Builder(this).setTitle("章の名前").setView(input).setPositiveButton("保存",(d,w)->{
   String title=input.getText().toString().trim();if(title.isEmpty()){message("章の名前を入力してください");return;}action.apply(title);
  }).setNegativeButton("戻る",null).show();
 }
 private void replace(int index,BookStore.Chapter replacement){
  List<BookStore.Chapter> all=db.listChapters(bookId);all.set(index,replacement);save(all);
 }
 private void save(List<BookStore.Chapter> chapters){
  chapters.sort(Comparator.comparingInt(c->c.startSeq));
  try{db.editChapters(bookId,chapters);refresh();}catch(Exception e){message(e.getMessage()==null?"章を変更できませんでした":e.getMessage());}
 }
 private void chooseBoundary(IntConsumer selected){
  List<BookStore.Paragraph> paragraphs=db.listParagraphs(bookId);
  if(paragraphs.size()<2){message("章の境界にできる段落がありません");return;}
  List<Group> groups=new ArrayList<>();String previous="";
  for(int i=0;i<paragraphs.size();i++){
   BookStore.Paragraph p=paragraphs.get(i);
   String key=p.screen>0?"画面 "+p.screen:"段落 "+(i/25+1)+"〜";
   if(!key.equals(previous)){groups.add(new Group(key));previous=key;}
   groups.get(groups.size()-1).paragraphs.add(p);
  }
  String[] names=new String[groups.size()];
  for(int i=0;i<groups.size();i++)names[i]=groups.get(i).name+"  "+shortText(groups.get(i).paragraphs.get(0).text,22);
  new AlertDialog.Builder(this).setTitle("開始画面を選ぶ").setItems(names,(d,which)->chooseParagraph(groups.get(which),selected)).setNegativeButton("戻る",null).show();
 }
 private void chooseParagraph(Group group,IntConsumer selected){
  String[] names=new String[group.paragraphs.size()];
  for(int i=0;i<names.length;i++)names[i]=(group.paragraphs.get(i).seq+1)+": "+shortText(group.paragraphs.get(i).text,65);
  new AlertDialog.Builder(this).setTitle("章の開始段落を選ぶ").setItems(names,(d,which)->selected.accept(group.paragraphs.get(which).seq)).setNegativeButton("戻る",null).show();
 }
 private static String shortText(String value,int limit){return value.length()<=limit?value:value.substring(0,limit)+"…";}
 @Override protected void onDestroy(){if(db!=null)db.close();super.onDestroy();}
}
