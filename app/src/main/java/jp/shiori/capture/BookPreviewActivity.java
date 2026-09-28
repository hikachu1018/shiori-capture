package jp.shiori.capture;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-screen OCR inspection with the preserved cover and illustration images. */
public final class BookPreviewActivity extends Activity {
 private static final class Page {
  final int screen;final List<BookStore.Paragraph> paragraphs=new ArrayList<>();
  BookStore.PageImage image;
  Page(int screen){this.screen=screen;}
 }
 private BookStore db;private long bookId;private List<Page> pages;private int position;private Spinner picker;
 private TextView heading,detail,ocr;private ImageView picture;private ScrollView scroll;private Button previous,next;
 private int dp(int value){return Ui.dp(this,value);}
 @Override public void onCreate(Bundle state){super.onCreate(state);
  bookId=getIntent().getLongExtra("bookId",-1);db=new BookStore(this);BookStore.Book book=db.getBook(bookId);if(book==null){finish();return;}
  Map<Integer,Page> ordered=new LinkedHashMap<>();
  for(BookStore.Paragraph p:db.listAllParagraphs(bookId))ordered.computeIfAbsent(p.screen,Page::new).paragraphs.add(p);
  for(BookStore.PageImage image:db.listPageImages(bookId))ordered.computeIfAbsent(image.screen,Page::new).image=image;
  pages=new ArrayList<>(ordered.values());
  int requested=getIntent().getIntExtra("screen",-1);
  for(int i=0;i<pages.size();i++)if(pages.get(i).screen>=requested&&requested>=0){position=i;break;}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BACKGROUND);setContentView(root);
  root.setOnApplyWindowInsetsListener((v,in)->{Insets i=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom);return in;});
  LinearLayout top=new LinearLayout(this);top.setPadding(dp(20),dp(15),dp(20),dp(6));top.setOrientation(LinearLayout.VERTICAL);root.addView(top);
  top.addView(Ui.button(this,"本棚・読書画面へ戻る",Ui.PLAIN,this::finish),new LinearLayout.LayoutParams(-1,dp(46)));
  top.addView(Ui.text(this,book.title,21,Ui.INK,true),Ui.margins(this,8,0));
  top.addView(Ui.text(this,"OCR本文と保存した画像を画面ごとに確認できます。",13,Ui.MUTED,false),Ui.margins(this,4,0));
  String[] labels=new String[pages.size()];for(int i=0;i<pages.size();i++)labels[i]=pages.get(i).screen==0?"取り込み本文":"画面 "+pages.get(i).screen;
  picker=new Spinner(this);picker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));top.addView(picker,new LinearLayout.LayoutParams(-1,dp(48)));
  scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),dp(24));scroll.addView(body);
  heading=Ui.text(this,"",20,Ui.INK,true);body.addView(heading);
  detail=Ui.text(this,"",13,Ui.MUTED,false);body.addView(detail,Ui.margins(this,6,0));
  picture=new ImageView(this);picture.setAdjustViewBounds(true);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);picture.setMaxHeight(dp(500));
  picture.setContentDescription("保存したページ画像");body.addView(picture,Ui.margins(this,15,0));
  ocr=Ui.text(this,"",16,Ui.INK,false);ocr.setTextIsSelectable(true);ocr.setLineSpacing(dp(5),1f);body.addView(ocr,Ui.margins(this,16,0));
  LinearLayout controls=new LinearLayout(this);controls.setPadding(dp(20),dp(10),dp(20),dp(14));root.addView(controls);
  previous=Ui.button(this,"前の画面",Ui.SECONDARY,()->move(-1));next=Ui.button(this,"次の画面",Ui.PRIMARY,()->move(1));
  LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(52),1);left.rightMargin=dp(8);controls.addView(previous,left);controls.addView(next,new LinearLayout.LayoutParams(0,dp(52),1));
  picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(AdapterView<?> parent,View view,int at,long id){position=at;showPage();}
   @Override public void onNothingSelected(AdapterView<?> parent){}});
  if(!pages.isEmpty())picker.setSelection(position);showPage();
 }
 private void move(int amount){position=Math.max(0,Math.min(pages.size()-1,position+amount));picker.setSelection(position);showPage();}
 private void showPage(){
  if(pages.isEmpty()){heading.setText("本文はまだありません");detail.setText("");ocr.setText("");picture.setVisibility(View.GONE);previous.setEnabled(false);next.setEnabled(false);return;}
  Page page=pages.get(position);heading.setText((page.screen==0?"取り込み本文":"画面 "+page.screen)+"  ·  "+(position+1)+" / "+pages.size());
  int visible=0;StringBuilder text=new StringBuilder();
  for(BookStore.Paragraph p:page.paragraphs){if(!p.hidden)visible++;if(text.length()>0)text.append("\n\n");if(p.hidden)text.append("【読書本文から除外】");text.append(p.text);}
  detail.setText("OCR "+page.paragraphs.size()+"段落 · 本文に採用 "+visible+"段落"+(page.image==null?"":" · 画像として保存"));
  ocr.setText(text.length()==0?"OCR本文はありません。":text.toString());
  if(page.image==null){picture.setImageDrawable(null);picture.setVisibility(View.GONE);}
  else{
   File file=db.imageFile(bookId,page.screen);Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());
   picture.setImageBitmap(bitmap);picture.setVisibility(bitmap==null?View.GONE:View.VISIBLE);
  }
  previous.setEnabled(position>0);next.setEnabled(position+1<pages.size());scroll.scrollTo(0,0);
 }
 @Override protected void onDestroy(){if(db!=null)db.close();super.onDestroy();}
}
