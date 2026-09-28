package jp.shiori.capture;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Durable, private text library. Each OCR screen is committed before the next one is processed. */
final class BookStore extends SQLiteOpenHelper {
 static final class Book {
  long id,createdAt;
  String title,state;
  int readSeq,readOffset;
  Book(long id,String title,String state,long createdAt,int readSeq,int readOffset){
   this.id=id;this.title=title;this.state=state;this.createdAt=createdAt;this.readSeq=readSeq;this.readOffset=readOffset;
  }
 }
 static final class Paragraph {
  final int seq,screen;
  final String text;
  final boolean hidden;
  Paragraph(int seq,int screen,String text){this(seq,screen,text,false);}
  Paragraph(int seq,int screen,String text,boolean hidden){this.seq=seq;this.screen=screen;this.text=text;this.hidden=hidden;}
 }
 static final class PageImage {
  final int screen,startSeq;final String kind;
  PageImage(int screen,int startSeq,String kind){this.screen=screen;this.startSeq=startSeq;this.kind=kind;}
 }
 static final class Chapter {
  final int startSeq;
  final String title;
  Chapter(int startSeq,String title){this.startSeq=startSeq;this.title=title;}
 }
 static final class CaptureCheckpoint {
  final int lastScreen;
  final String lastText;
  final Long lastHash;
  CaptureCheckpoint(int lastScreen,String lastText,Long lastHash){this.lastScreen=lastScreen;this.lastText=lastText;this.lastHash=lastHash;}
 }

 private final File imageDirectory;
 BookStore(Context context){super(context,"shiori_books.db",null,7);imageDirectory=new File(context.getFilesDir(),"page-images");}
 @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
 @Override public void onCreate(SQLiteDatabase db){
  db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,read_seq INTEGER NOT NULL DEFAULT 0,read_offset INTEGER NOT NULL DEFAULT 0,chapters_edited INTEGER NOT NULL DEFAULT 0,last_hash INTEGER,auto_title INTEGER NOT NULL DEFAULT 0)");
  db.execSQL("CREATE TABLE paragraphs(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,seq INTEGER NOT NULL,screen INTEGER NOT NULL,text TEXT NOT NULL,hidden INTEGER NOT NULL DEFAULT 0,UNIQUE(book_id,seq))");
  db.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,start_seq INTEGER NOT NULL,title TEXT NOT NULL,UNIQUE(book_id,start_seq))");
  db.execSQL("CREATE TABLE page_images(book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,screen INTEGER NOT NULL,start_seq INTEGER NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(book_id,screen))");
  db.execSQL("CREATE INDEX paragraph_book_seq ON paragraphs(book_id,seq)");
  db.execSQL("CREATE INDEX chapter_book_seq ON chapters(book_id,start_seq)");
 }
 @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
  boolean needsChapterRebuild=oldVersion<7;
  if(oldVersion==1&&newVersion>=2){
   db.execSQL("ALTER TABLE books ADD COLUMN chapters_edited INTEGER NOT NULL DEFAULT 0");
   db.execSQL("ALTER TABLE books ADD COLUMN last_hash INTEGER");
   // v0.3.0 did not record whether a chapter was edited; preserve its current boundaries.
   db.execSQL("UPDATE books SET chapters_edited=1");
   oldVersion=2;
  }
  if(oldVersion==2&&newVersion>=3){removeSavedKindleProgress(db);oldVersion=3;}
  // Revisit v3 libraries: the old exact pattern missed partial and split OCR results.
  if(oldVersion==3&&newVersion>=4){removeSavedKindleProgress(db);oldVersion=4;}
  if(oldVersion==4&&newVersion>=5){
   db.execSQL("ALTER TABLE paragraphs ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0");
   oldVersion=5;
  }
  if(oldVersion==5&&newVersion>=6){
   db.execSQL("ALTER TABLE books ADD COLUMN auto_title INTEGER NOT NULL DEFAULT 0");
   db.execSQL("CREATE TABLE page_images(book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,screen INTEGER NOT NULL,start_seq INTEGER NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(book_id,screen))");
   oldVersion=6;
  }
  if(oldVersion==6&&newVersion>=7)oldVersion=7;
  if(needsChapterRebuild&&oldVersion>=7)try(Cursor c=db.rawQuery("SELECT id,chapters_edited FROM books WHERE state!='capturing'",null)){
   while(c.moveToNext())rebuildInDatabase(db,c.getLong(0),c.getInt(1)!=0);
  }
  if(oldVersion!=newVersion)throw new IllegalStateException("未対応の本棚データです");
 }

 private static final class SavedLine {
  final long id,bookId;final int screen;final String text;
  SavedLine(long id,long bookId,int screen,String text){this.id=id;this.bookId=bookId;this.screen=screen;this.text=text;}
 }

 private static final class SavedChange {
  final long id;final String text;
  SavedChange(long id,String text){this.id=id;this.text=text;}
 }

 private static void cleanSavedScreen(List<SavedLine> rows,List<SavedChange> changes){
  if(rows.isEmpty())return;
  List<String> original=new ArrayList<>(rows.size());for(SavedLine row:rows)original.add(row.text);
  List<String> cleaned=KindleProgressFilter.cleanLines(original);
  for(int i=0;i<rows.size();i++){
   SavedLine row=rows.get(i);String value=cleaned.get(i);
   if(!value.equals(row.text))changes.add(new SavedChange(row.id,value));
  }
 }

 private static void removeSavedKindleProgress(SQLiteDatabase db){
  List<SavedLine> screenRows=new ArrayList<>();long currentBook=-1;int currentScreen=-1;
  List<SavedChange> changes=new ArrayList<>();
  try(Cursor c=db.rawQuery("SELECT id,book_id,screen,text FROM paragraphs ORDER BY book_id,screen,seq",null)){
   while(c.moveToNext()){
    SavedLine row=new SavedLine(c.getLong(0),c.getLong(1),c.getInt(2),c.getString(3));
    if(!screenRows.isEmpty()&&(row.bookId!=currentBook||row.screen!=currentScreen||row.screen==0)){
     cleanSavedScreen(screenRows,changes);screenRows.clear();
    }
    screenRows.add(row);currentBook=row.bookId;currentScreen=row.screen;
   }
  }
  cleanSavedScreen(screenRows,changes);
  for(SavedChange change:changes){
   if(change.text.isEmpty())db.delete("paragraphs","id=?",new String[]{Long.toString(change.id)});
   else {ContentValues update=new ContentValues();update.put("text",change.text);db.update("paragraphs",update,"id=?",new String[]{Long.toString(change.id)});}
  }
 }

 long createBook(String title){
  SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();
  db.beginTransaction();
  try{
   boolean automatic=title.trim().isEmpty();
   String name=automatic?"撮影した本 "+new SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.JAPAN).format(new Date(now)):title.trim();
   ContentValues b=new ContentValues();b.put("title",name);b.put("auto_title",automatic?1:0);b.put("state","capturing");b.put("created_at",now);b.put("updated_at",now);
   long id=db.insertOrThrow("books",null,b);
   ContentValues ch=new ContentValues();ch.put("book_id",id);ch.put("start_seq",0);ch.put("title","冒頭");db.insertOrThrow("chapters",null,ch);
   db.setTransactionSuccessful();return id;
  }finally{db.endTransaction();}
 }
 File imageFile(long bookId,int screen){return new File(imageDirectory,bookId+"-"+screen+".webp");}
 void appendScreen(long bookId,int screen,String text,long hash,String imageKind,byte[] imageData,String detectedTitle){
  if((imageKind==null)!=(imageData==null))throw new IllegalArgumentException("画像データが正しくありません");
  SQLiteDatabase db=getWritableDatabase();File image=null;boolean committed=false;db.beginTransaction();
  try{
   int seq=0;
   try(Cursor c=db.rawQuery("SELECT MAX(seq) FROM paragraphs WHERE book_id=?",new String[]{Long.toString(bookId)})){
    if(c.moveToFirst()&&!c.isNull(0))seq=c.getInt(0)+1;
   }
   int firstSeq=seq;boolean saved=false;
   for(String raw:KindleProgressFilter.cleanLines(java.util.Arrays.asList(text.split("\\n",-1)))){
    String line=raw.trim();
    if(line.isEmpty())continue;
    ContentValues p=new ContentValues();p.put("book_id",bookId);p.put("seq",seq++);p.put("screen",screen);p.put("text",line);p.put("hidden",imageData==null?0:1);
    db.insertOrThrow("paragraphs",null,p);saved=true;
   }
   if(!saved){ContentValues p=new ContentValues();p.put("book_id",bookId);p.put("seq",seq);p.put("screen",screen);p.put("text",imageData==null?"［文字を認識できませんでした］":"［画像］");p.put("hidden",imageData==null?0:1);db.insertOrThrow("paragraphs",null,p);}
   if(imageData!=null){
    if(!imageDirectory.isDirectory()&&!imageDirectory.mkdirs())throw new IOException("画像保存先を作れません");
    image=imageFile(bookId,screen);File temp=new File(imageDirectory,bookId+"-"+screen+".tmp");
    try(FileOutputStream stream=new FileOutputStream(temp)){stream.write(imageData);}
    if(!temp.renameTo(image))throw new IOException("画像を保存できません");
    ContentValues asset=new ContentValues();asset.put("book_id",bookId);asset.put("screen",screen);asset.put("start_seq",firstSeq);asset.put("kind",imageKind);
    db.insertOrThrow("page_images",null,asset);
   }
   ContentValues b=new ContentValues();b.put("updated_at",System.currentTimeMillis());b.put("last_hash",hash);db.update("books",b,"id=?",new String[]{Long.toString(bookId)});
   if(detectedTitle!=null&&!detectedTitle.isEmpty()){
    ContentValues suggested=new ContentValues();suggested.put("title",detectedTitle);suggested.put("auto_title",0);
    db.update("books",suggested,"id=? AND auto_title=1",new String[]{Long.toString(bookId)});
   }
   db.setTransactionSuccessful();committed=true;
  }catch(IOException e){throw new IllegalStateException(e.getMessage(),e);}
  finally{db.endTransaction();if(!committed&&image!=null)image.delete();}
 }
 void finishCapture(long bookId,boolean complete){
  if(!chaptersEdited(bookId))rebuildChapters(bookId,false);
  ContentValues b=new ContentValues();b.put("state",complete?"complete":"partial");b.put("updated_at",System.currentTimeMillis());
  getWritableDatabase().update("books",b,"id=?",new String[]{Long.toString(bookId)});
 }
 void markPartial(long bookId){
  Book book=getBook(bookId);
  if(book==null||!"capturing".equals(book.state))return;
  if(!chaptersEdited(bookId))rebuildChapters(bookId,false);
  ContentValues b=new ContentValues();b.put("state","partial");
  getWritableDatabase().update("books",b,"id=? AND state='capturing'",new String[]{Long.toString(bookId)});
 }
 private boolean chaptersEdited(long bookId){
  try(Cursor c=getReadableDatabase().rawQuery("SELECT chapters_edited FROM books WHERE id=?",new String[]{Long.toString(bookId)})){
   return c.moveToFirst()&&c.getInt(0)!=0;
  }
 }
 CaptureCheckpoint resumeCapture(long bookId){
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
  try{
   try(Cursor state=db.rawQuery("SELECT state,last_hash FROM books WHERE id=?",new String[]{Long.toString(bookId)})){
    if(!state.moveToFirst()||!"partial".equals(state.getString(0)))throw new IllegalStateException("この本は撮影を再開できません");
    Long hash=state.isNull(1)?null:state.getLong(1);
    int lastScreen=0;StringBuilder text=new StringBuilder();
    try(Cursor screen=db.rawQuery("SELECT MAX(screen) FROM paragraphs WHERE book_id=?",new String[]{Long.toString(bookId)})){
     if(screen.moveToFirst()&&!screen.isNull(0))lastScreen=screen.getInt(0);
    }
    if(lastScreen>0)try(Cursor lines=db.rawQuery("SELECT text FROM paragraphs WHERE book_id=? AND screen=? ORDER BY seq",new String[]{Long.toString(bookId),Integer.toString(lastScreen)})){
     while(lines.moveToNext()){if(text.length()>0)text.append('\n');text.append(lines.getString(0));}
    }
    ContentValues values=new ContentValues();values.put("state","capturing");values.put("updated_at",System.currentTimeMillis());
    db.update("books",values,"id=?",new String[]{Long.toString(bookId)});
    db.setTransactionSuccessful();return new CaptureCheckpoint(lastScreen,text.toString(),hash);
   }
  }finally{db.endTransaction();}
 }
 int countScreens(long bookId){
  try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT screen) FROM paragraphs WHERE book_id=? AND screen>0",new String[]{Long.toString(bookId)})){
   return c.moveToFirst()?c.getInt(0):0;
  }
 }
 void renameBook(long bookId,String title){
  if(title.trim().isEmpty())throw new IllegalArgumentException("本の名前を入力してください");
  ContentValues b=new ContentValues();b.put("title",title.trim());b.put("auto_title",0);b.put("updated_at",System.currentTimeMillis());
  getWritableDatabase().update("books",b,"id=?",new String[]{Long.toString(bookId)});
 }
 void deleteBook(long bookId){
  SQLiteDatabase db=getWritableDatabase();List<File> images=new ArrayList<>();boolean deleted=false;db.beginTransaction();
  try{
   try(Cursor c=db.rawQuery("SELECT state FROM books WHERE id=?",new String[]{Long.toString(bookId)})){
    if(!c.moveToFirst())throw new IllegalArgumentException("本が見つかりません");
    if("capturing".equals(c.getString(0)))throw new IllegalStateException("撮影中の本は一時停止してから削除してください");
   }
   try(Cursor c=db.rawQuery("SELECT screen FROM page_images WHERE book_id=?",new String[]{Long.toString(bookId)})){
    while(c.moveToNext())images.add(imageFile(bookId,c.getInt(0)));
   }
   if(db.delete("books","id=?",new String[]{Long.toString(bookId)})!=1)throw new IllegalStateException("本を削除できませんでした");
   db.setTransactionSuccessful();deleted=true;
  }finally{db.endTransaction();if(deleted)for(File image:images)image.delete();}
 }
 Book getBook(long id){
  try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,state,created_at,read_seq,read_offset FROM books WHERE id=?",new String[]{Long.toString(id)})){
   return c.moveToFirst()?new Book(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4),c.getInt(5)):null;
  }
 }
 List<Book> listBooks(){
  List<Book> out=new ArrayList<>();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,state,created_at,read_seq,read_offset FROM books ORDER BY updated_at DESC",null)){
   while(c.moveToNext())out.add(new Book(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4),c.getInt(5)));
  }
  return out;
 }
 List<Paragraph> listParagraphs(long bookId){
  List<Paragraph> out=new ArrayList<>();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,screen,text FROM paragraphs WHERE book_id=? AND hidden=0 ORDER BY seq",new String[]{Long.toString(bookId)})){
   while(c.moveToNext())out.add(new Paragraph(c.getInt(0),c.getInt(1),c.getString(2)));
  }
  return out;
 }
 List<Paragraph> listAllParagraphs(long bookId){
  List<Paragraph> out=new ArrayList<>();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,screen,text,hidden FROM paragraphs WHERE book_id=? ORDER BY seq",new String[]{Long.toString(bookId)})){
   while(c.moveToNext())out.add(new Paragraph(c.getInt(0),c.getInt(1),c.getString(2),c.getInt(3)!=0));
  }
  return out;
 }
 List<PageImage> listPageImages(long bookId){
  List<PageImage> out=new ArrayList<>();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT screen,start_seq,kind FROM page_images WHERE book_id=? ORDER BY screen",new String[]{Long.toString(bookId)})){
   while(c.moveToNext())out.add(new PageImage(c.getInt(0),c.getInt(1),c.getString(2)));
  }
  return out;
 }
 File firstPageImage(long bookId){
  for(PageImage page:listPageImages(bookId))if("cover".equals(page.kind)&&imageFile(bookId,page.screen).isFile())return imageFile(bookId,page.screen);
  for(PageImage page:listPageImages(bookId))if(imageFile(bookId,page.screen).isFile())return imageFile(bookId,page.screen);
  return null;
 }
 private static ChapterDetector.Analysis rebuildInDatabase(SQLiteDatabase db,long bookId){return rebuildInDatabase(db,bookId,false);}
 private static ChapterDetector.Analysis rebuildInDatabase(SQLiteDatabase db,long bookId,boolean preserveChapters){
  List<Paragraph> all=new ArrayList<>();
  try(Cursor c=db.rawQuery("SELECT seq,screen,text FROM paragraphs WHERE book_id=? ORDER BY seq",new String[]{Long.toString(bookId)})){
   while(c.moveToNext())all.add(new Paragraph(c.getInt(0),c.getInt(1),c.getString(2)));
  }
  // Imported EPUB chapters already carry their own boundaries and have screen 0.
  if(all.isEmpty()||all.get(0).screen==0)return null;
  Set<Integer> imageScreens=new HashSet<>();
  try(Cursor c=db.rawQuery("SELECT screen FROM page_images WHERE book_id=?",new String[]{Long.toString(bookId)})){
   while(c.moveToNext())imageScreens.add(c.getInt(0));
  }
  // Earlier versions retained OCR from sparse title pages when the artwork had little ink.
  for(int screen=1;screen<=3;screen++){
   if(imageScreens.contains(screen))continue;
   StringBuilder page=new StringBuilder();
   for(Paragraph p:all)if(p.screen==screen){if(page.length()>0)page.append('\n');page.append(p.text);}
   if(!CapturePageClassifier.looksLikeCoverText(screen,page.toString()))continue;
   imageScreens.add(screen);
   String title=CapturePageClassifier.title("cover",page.toString());
   if(title!=null){ContentValues name=new ContentValues();name.put("title",title);name.put("auto_title",0);
    db.update("books",name,"id=? AND auto_title=1",new String[]{Long.toString(bookId)});}
  }
  List<Paragraph> textOnly=new ArrayList<>();Set<Integer> imageSeqs=new HashSet<>();
  for(Paragraph p:all){if(imageScreens.contains(p.screen))imageSeqs.add(p.seq);else textOnly.add(p);}
  ChapterDetector.Analysis result=ChapterDetector.analyze(textOnly);
  db.execSQL("UPDATE paragraphs SET hidden=0 WHERE book_id=?",new Object[]{bookId});
  ContentValues hidden=new ContentValues();hidden.put("hidden",1);
  imageSeqs.addAll(result.hiddenSeqs);
  for(int seq:imageSeqs)db.update("paragraphs",hidden,"book_id=? AND seq=?",new String[]{Long.toString(bookId),Integer.toString(seq)});
  // Without a body match, keep existing chapter boundaries while excluding navigation text.
  if(preserveChapters||(result.tocCount>0&&result.matchedCount==0))return result;
  db.delete("chapters","book_id=?",new String[]{Long.toString(bookId)});
  for(Chapter ch:result.chapters){ContentValues value=new ContentValues();value.put("book_id",bookId);value.put("start_seq",ch.startSeq);value.put("title",ch.title);db.insertOrThrow("chapters",null,value);}
  return result;
 }
 ChapterDetector.Analysis rebuildChapters(long bookId,boolean overrideManual){
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
  try{
   try(Cursor c=db.rawQuery("SELECT chapters_edited FROM books WHERE id=?",new String[]{Long.toString(bookId)})){
    if(!c.moveToFirst())throw new IllegalArgumentException("本が見つかりません");
    if(c.getInt(0)!=0&&!overrideManual)return null;
   }
   ChapterDetector.Analysis result=rebuildInDatabase(db,bookId);
   if(overrideManual&&result!=null&&(result.tocCount==0||result.matchedCount>0)){ContentValues b=new ContentValues();b.put("chapters_edited",0);db.update("books",b,"id=?",new String[]{Long.toString(bookId)});}
   db.setTransactionSuccessful();return result;
  }finally{db.endTransaction();}
 }
 List<Chapter> listChapters(long bookId){
  List<Chapter> out=new ArrayList<>();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT start_seq,title FROM chapters WHERE book_id=? ORDER BY start_seq",new String[]{Long.toString(bookId)})){
   while(c.moveToNext())out.add(new Chapter(c.getInt(0),c.getString(1)));
  }
  return out;
 }
 void replaceChapters(long bookId,List<Chapter> chapters){
  if(chapters.isEmpty()||chapters.get(0).startSeq!=0)throw new IllegalArgumentException("最初の章は本文の先頭から始めてください");
  int previous=-1;
  for(Chapter ch:chapters){if(ch.startSeq<=previous||ch.title.trim().isEmpty())throw new IllegalArgumentException("章の位置または名前が正しくありません");previous=ch.startSeq;}
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
  try{
   db.delete("chapters","book_id=?",new String[]{Long.toString(bookId)});
   for(Chapter ch:chapters){ContentValues v=new ContentValues();v.put("book_id",bookId);v.put("start_seq",ch.startSeq);v.put("title",ch.title.trim());db.insertOrThrow("chapters",null,v);}
   db.setTransactionSuccessful();
  }finally{db.endTransaction();}
 }
 void editChapters(long bookId,List<Chapter> chapters){
  replaceChapters(bookId,chapters);
  ContentValues values=new ContentValues();values.put("chapters_edited",1);
  getWritableDatabase().update("books",values,"id=?",new String[]{Long.toString(bookId)});
 }
 void saveProgress(long bookId,int seq,int offset){
  ContentValues b=new ContentValues();b.put("read_seq",seq);b.put("read_offset",offset);b.put("updated_at",System.currentTimeMillis());
  getWritableDatabase().update("books",b,"id=?",new String[]{Long.toString(bookId)});
 }
 long importBook(BookArchive.Imported imported){
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
  try{
   long now=System.currentTimeMillis();ContentValues b=new ContentValues();b.put("title",imported.title);b.put("state","complete");b.put("created_at",now);b.put("updated_at",now);
   long id=db.insertOrThrow("books",null,b);int seq=0;
   for(BookArchive.ImportedChapter chapter:imported.chapters){
    ContentValues ch=new ContentValues();ch.put("book_id",id);ch.put("start_seq",seq);ch.put("title",chapter.title);db.insertOrThrow("chapters",null,ch);
    boolean saved=false;
    for(String raw:KindleProgressFilter.cleanLines(chapter.paragraphs)){
     String line=raw.trim();
     if(line.isEmpty())continue;
     ContentValues p=new ContentValues();p.put("book_id",id);p.put("seq",seq++);p.put("screen",0);p.put("text",line);db.insertOrThrow("paragraphs",null,p);saved=true;
    }
    if(!saved){ContentValues p=new ContentValues();p.put("book_id",id);p.put("seq",seq++);p.put("screen",0);p.put("text","［本文がありません］");db.insertOrThrow("paragraphs",null,p);}
   }
   db.setTransactionSuccessful();return id;
  }finally{db.endTransaction();}
 }
}
