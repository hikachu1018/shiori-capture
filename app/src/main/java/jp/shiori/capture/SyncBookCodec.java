package jp.shiori.capture;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Version-one book snapshot shared with the Windows companion. */
final class SyncBookCodec {
 static final class State {
  final long id,updatedAt,syncedAt,deletedAt;final String uuid;final int revision;final boolean contentDirty;
  State(long id,String uuid,int revision,long updatedAt,long syncedAt,long deletedAt,boolean contentDirty){this.id=id;this.uuid=uuid;this.revision=revision;this.updatedAt=updatedAt;this.syncedAt=syncedAt;this.deletedAt=deletedAt;this.contentDirty=contentDirty;}
  boolean dirty(){return updatedAt>syncedAt;}
 }
 static Map<String,State> states(BookStore store){Map<String,State> out=new HashMap<>();
  try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,uuid,sync_revision,updated_at,sync_synced_at,deleted_at,sync_content_dirty FROM books",null)){
   while(c.moveToNext())out.put(c.getString(1),new State(c.getLong(0),c.getString(1),c.getInt(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getInt(6)!=0));
  }return out;
 }
 static JSONObject exportBook(BookStore store,long id)throws Exception{
  SQLiteDatabase db=store.getReadableDatabase();JSONObject book=new JSONObject();book.put("format",1);
  try(Cursor c=db.rawQuery("SELECT uuid,title,state,created_at,updated_at,read_seq,read_offset,deleted_at,chapters_edited FROM books WHERE id=?",new String[]{Long.toString(id)})){
   if(!c.moveToFirst())throw new IllegalArgumentException("本が見つかりません");
   book.put("uuid",c.getString(0));book.put("title",c.getString(1));book.put("state","capturing".equals(c.getString(2))?"partial":c.getString(2));
   book.put("created_at",c.getLong(3));book.put("updated_at",c.getLong(4));book.put("read_seq",c.getInt(5));book.put("read_offset",c.getInt(6));book.put("deleted_at",c.getLong(7));book.put("chapters_edited",c.getInt(8)!=0);
  }
  JSONArray paragraphs=new JSONArray();try(Cursor c=db.rawQuery("SELECT uuid,seq,screen,text,hidden FROM paragraphs WHERE book_id=? ORDER BY seq",new String[]{Long.toString(id)})){
   while(c.moveToNext()){JSONObject p=new JSONObject();p.put("uuid",c.getString(0));p.put("seq",c.getInt(1));p.put("screen",c.getInt(2));p.put("text",c.getString(3));p.put("hidden",c.getInt(4)!=0);paragraphs.put(p);}
  }book.put("paragraphs",paragraphs);
  JSONArray chapters=new JSONArray();try(Cursor c=db.rawQuery("SELECT uuid,start_seq,title FROM chapters WHERE book_id=? ORDER BY start_seq",new String[]{Long.toString(id)})){
   while(c.moveToNext()){JSONObject ch=new JSONObject();ch.put("uuid",c.getString(0));ch.put("start_seq",c.getInt(1));ch.put("title",c.getString(2));chapters.put(ch);}
  }book.put("chapters",chapters);
  JSONArray images=new JSONArray();try(Cursor c=db.rawQuery("SELECT uuid,screen,start_seq,kind FROM page_images WHERE book_id=? ORDER BY screen",new String[]{Long.toString(id)})){
   while(c.moveToNext()){File file=store.imageFile(id,c.getInt(1));if(!file.isFile())continue;
    JSONObject image=new JSONObject();image.put("uuid",c.getString(0));image.put("screen",c.getInt(1));image.put("start_seq",c.getInt(2));image.put("kind",c.getString(3));
    image.put("data",Base64.encodeToString(Files.readAllBytes(file.toPath()),Base64.NO_WRAP));images.put(image);
   }
  }book.put("images",images);return book;
 }
 static void markSynced(BookStore store,long id,int revision,long exportedUpdatedAt){
  ContentValues values=new ContentValues();values.put("sync_revision",revision);values.put("sync_synced_at",exportedUpdatedAt);
  store.getWritableDatabase().update("books",values,"id=?",new String[]{Long.toString(id)});
  store.getWritableDatabase().execSQL("UPDATE books SET sync_content_dirty=0 WHERE id=? AND updated_at<=?",new Object[]{id,exportedUpdatedAt});
 }
 static long importBook(BookStore store,JSONObject book,int revision)throws Exception{
  validate(book);SQLiteDatabase db=store.getWritableDatabase();String uuid=book.getString("uuid");long now=System.currentTimeMillis();
  List<File> oldImages=new ArrayList<>();List<File> newImages=new ArrayList<>();List<File> staged=new ArrayList<>();
  Map<File,File> backups=new HashMap<>();long id=-1;boolean success=false;db.beginTransaction();
  try{
   try(Cursor c=db.rawQuery("SELECT id FROM books WHERE uuid=?",new String[]{uuid})){if(c.moveToFirst())id=c.getLong(0);}
   if(id>0){try(Cursor c=db.rawQuery("SELECT screen FROM page_images WHERE book_id=?",new String[]{Long.toString(id)})){while(c.moveToNext())oldImages.add(store.imageFile(id,c.getInt(0)));}
    db.delete("paragraphs","book_id=?",new String[]{Long.toString(id)});db.delete("chapters","book_id=?",new String[]{Long.toString(id)});db.delete("page_images","book_id=?",new String[]{Long.toString(id)});
   }
   ContentValues b=new ContentValues();b.put("uuid",uuid);b.put("title",book.getString("title"));b.put("state",book.getString("state"));
   b.put("created_at",book.getLong("created_at"));b.put("updated_at",now);b.put("read_seq",book.optInt("read_seq",0));b.put("read_offset",book.optInt("read_offset",0));
   b.put("deleted_at",book.optLong("deleted_at",0));b.put("sync_revision",revision);b.put("sync_synced_at",now);b.put("sync_content_dirty",0);
   b.put("chapters_edited",book.optBoolean("chapters_edited",false)?1:0);
   if(id>0)db.update("books",b,"id=?",new String[]{Long.toString(id)});else id=db.insertOrThrow("books",null,b);
   JSONArray paragraphs=book.getJSONArray("paragraphs");for(int i=0;i<paragraphs.length();i++){
    JSONObject p=paragraphs.getJSONObject(i);ContentValues v=new ContentValues();v.put("book_id",id);v.put("uuid",p.getString("uuid"));v.put("seq",p.getInt("seq"));v.put("screen",p.getInt("screen"));v.put("text",p.getString("text"));v.put("hidden",p.optBoolean("hidden")?1:0);db.insertOrThrow("paragraphs",null,v);
   }
   JSONArray chapters=book.getJSONArray("chapters");for(int i=0;i<chapters.length();i++){
    JSONObject ch=chapters.getJSONObject(i);ContentValues v=new ContentValues();v.put("book_id",id);v.put("uuid",ch.getString("uuid"));v.put("start_seq",ch.getInt("start_seq"));v.put("title",ch.getString("title"));db.insertOrThrow("chapters",null,v);
   }
   JSONArray images=book.getJSONArray("images");for(int i=0;i<images.length();i++){
    JSONObject image=images.getJSONObject(i);int screen=image.getInt("screen");byte[] data=Base64.decode(image.getString("data"),Base64.DEFAULT);
    if(data.length>8*1024*1024)throw new IOException("画像が大きすぎます");
    File file=store.imageFile(id,screen);File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("画像保存先を作れません");
    File temp=new File(parent,file.getName()+".incoming-"+UUID.randomUUID());
    try(FileOutputStream output=new FileOutputStream(temp)){output.write(data);}staged.add(temp);newImages.add(file);
    ContentValues v=new ContentValues();v.put("book_id",id);v.put("uuid",image.getString("uuid"));v.put("screen",screen);v.put("start_seq",image.getInt("start_seq"));v.put("kind",image.getString("kind"));db.insertOrThrow("page_images",null,v);
   }
   for(File old:oldImages)if(old.isFile()){File backup=new File(old.getParentFile(),old.getName()+".backup-"+UUID.randomUUID());
    if(!old.renameTo(backup))throw new IOException("保存画像を退避できません");backups.put(old,backup);}
   for(int i=0;i<staged.size();i++)if(!staged.get(i).renameTo(newImages.get(i)))throw new IOException("画像を保存できません");
   db.setTransactionSuccessful();success=true;return id;
  }finally{
   try{db.endTransaction();}finally{
    for(File temp:staged)temp.delete();
    if(success){for(File backup:backups.values())backup.delete();}
    else{for(File file:newImages)file.delete();for(Map.Entry<File,File> entry:backups.entrySet())entry.getValue().renameTo(entry.getKey());}
   }
  }
 }
 static long saveConflictCopy(BookStore store,long id)throws Exception{
  JSONObject book=exportBook(store,id);book.put("uuid",UUID.randomUUID().toString());book.put("title",book.getString("title")+"（競合した変更）");
  for(String name:new String[]{"paragraphs","chapters","images"}){JSONArray array=book.getJSONArray(name);for(int i=0;i<array.length();i++)array.getJSONObject(i).put("uuid",UUID.randomUUID().toString());}
  return importBook(store,book,0);
 }
 private static void validate(JSONObject book)throws JSONException{
  if(book.getInt("format")!=1)throw new JSONException("未対応の本棚データです");
  UUID.fromString(book.getString("uuid"));if(book.getString("title").trim().isEmpty())throw new JSONException("本の名前がありません");
  if(!book.has("paragraphs")||!book.has("chapters")||!book.has("images"))throw new JSONException("本の内容が不足しています");
  if(book.getJSONArray("paragraphs").length()>100000||book.getJSONArray("images").length()>10000)throw new JSONException("本の項目数が多すぎます");
 }
}
