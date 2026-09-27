package jp.shiori.capture;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

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
  Paragraph(int seq,int screen,String text){this.seq=seq;this.screen=screen;this.text=text;}
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

 BookStore(Context context){super(context,"shiori_books.db",null,2);}
 @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
 @Override public void onCreate(SQLiteDatabase db){
  db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,read_seq INTEGER NOT NULL DEFAULT 0,read_offset INTEGER NOT NULL DEFAULT 0,chapters_edited INTEGER NOT NULL DEFAULT 0,last_hash INTEGER)");
  db.execSQL("CREATE TABLE paragraphs(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,seq INTEGER NOT NULL,screen INTEGER NOT NULL,text TEXT NOT NULL,UNIQUE(book_id,seq))");
  db.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,start_seq INTEGER NOT NULL,title TEXT NOT NULL,UNIQUE(book_id,start_seq))");
  db.execSQL("CREATE INDEX paragraph_book_seq ON paragraphs(book_id,seq)");
  db.execSQL("CREATE INDEX chapter_book_seq ON chapters(book_id,start_seq)");
 }
 @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
  if(oldVersion==1&&newVersion>=2){
   db.execSQL("ALTER TABLE books ADD COLUMN chapters_edited INTEGER NOT NULL DEFAULT 0");
   db.execSQL("ALTER TABLE books ADD COLUMN last_hash INTEGER");
   // v0.3.0 did not record whether a chapter was edited; preserve its current boundaries.
   db.execSQL("UPDATE books SET chapters_edited=1");
   return;
  }
  throw new IllegalStateException("未対応の本棚データです");
 }

 long createBook(String title){
  SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();
  db.beginTransaction();
  try{
   ContentValues b=new ContentValues();b.put("title",title.trim());b.put("state","capturing");b.put("created_at",now);b.put("updated_at",now);
   long id=db.insertOrThrow("books",null,b);
   ContentValues ch=new ContentValues();ch.put("book_id",id);ch.put("start_seq",0);ch.put("title","冒頭");db.insertOrThrow("chapters",null,ch);
   db.setTransactionSuccessful();return id;
  }finally{db.endTransaction();}
 }
 void appendScreen(long bookId,int screen,String text,long hash){
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
  try{
   int seq=0;
   try(Cursor c=db.rawQuery("SELECT MAX(seq) FROM paragraphs WHERE book_id=?",new String[]{Long.toString(bookId)})){
    if(c.moveToFirst()&&!c.isNull(0))seq=c.getInt(0)+1;
   }
   boolean saved=false;
   for(String raw:text.split("\\n")){
    String line=raw.trim();if(line.isEmpty())continue;
    ContentValues p=new ContentValues();p.put("book_id",bookId);p.put("seq",seq++);p.put("screen",screen);p.put("text",line);
    db.insertOrThrow("paragraphs",null,p);saved=true;
   }
   if(!saved){ContentValues p=new ContentValues();p.put("book_id",bookId);p.put("seq",seq);p.put("screen",screen);p.put("text","［文字を認識できませんでした］");db.insertOrThrow("paragraphs",null,p);}
   ContentValues b=new ContentValues();b.put("updated_at",System.currentTimeMillis());b.put("last_hash",hash);db.update("books",b,"id=?",new String[]{Long.toString(bookId)});
   db.setTransactionSuccessful();
  }finally{db.endTransaction();}
 }
 void finishCapture(long bookId,boolean complete){
  if(!chaptersEdited(bookId))replaceChapters(bookId,ChapterDetector.detect(listParagraphs(bookId)));
  ContentValues b=new ContentValues();b.put("state",complete?"complete":"partial");b.put("updated_at",System.currentTimeMillis());
  getWritableDatabase().update("books",b,"id=?",new String[]{Long.toString(bookId)});
 }
 void markPartial(long bookId){
  Book book=getBook(bookId);
  if(book==null||!"capturing".equals(book.state))return;
  if(!chaptersEdited(bookId))replaceChapters(bookId,ChapterDetector.detect(listParagraphs(bookId)));
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
  ContentValues b=new ContentValues();b.put("title",title.trim());b.put("updated_at",System.currentTimeMillis());
  getWritableDatabase().update("books",b,"id=?",new String[]{Long.toString(bookId)});
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
  try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,screen,text FROM paragraphs WHERE book_id=? ORDER BY seq",new String[]{Long.toString(bookId)})){
   while(c.moveToNext())out.add(new Paragraph(c.getInt(0),c.getInt(1),c.getString(2)));
  }
  return out;
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
    for(String line:chapter.paragraphs){ContentValues p=new ContentValues();p.put("book_id",id);p.put("seq",seq++);p.put("screen",0);p.put("text",line);db.insertOrThrow("paragraphs",null,p);}
   }
   db.setTransactionSuccessful();return id;
  }finally{db.endTransaction();}
 }
}
