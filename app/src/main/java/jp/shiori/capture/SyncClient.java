package jp.shiori.capture;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground-only, TLS certificate-pinned sync with one paired Windows PC. */
final class SyncClient {
 private static final AtomicBoolean syncInProgress=new AtomicBoolean();
 static final class Connection {
  final String host,fingerprint,code;
  Connection(String host,String fingerprint,String code){this.host=host;this.fingerprint=fingerprint;this.code=code;}
 }
 static Connection parseConnection(String text){
  String[] parts=text.trim().split("\\|",-1);
  if(parts.length!=3||!parts[0].matches("[A-Za-z0-9.:-]+:[0-9]{1,5}")||!parts[1].matches("[0-9a-fA-F]{64}")||!parts[2].matches("[0-9]{6}"))
   throw new IllegalArgumentException("PCの接続情報をそのまま貼り付けてください");
  return new Connection(parts[0],parts[1].toLowerCase(),parts[2]);
 }
 private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
 private static HttpsURLConnection open(String host,String pin,String token,String method,String path)throws Exception{
  X509TrustManager trust=new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
   public void checkClientTrusted(X509Certificate[] chain,String auth){}
   public void checkServerTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{
    if(chain==null||chain.length==0)throw new java.security.cert.CertificateException("PCの証明書がありません");
    try{String actual=hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
     if(!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),pin.getBytes(StandardCharsets.US_ASCII)))throw new java.security.cert.CertificateException("PCの証明書が変わりました。再ペアリングしてください");
    }catch(java.security.NoSuchAlgorithmException e){throw new java.security.cert.CertificateException(e);}
   }
  };
  SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{trust},null);
  HttpsURLConnection connection=(HttpsURLConnection)new URL("https://"+host+path).openConnection();
  connection.setSSLSocketFactory(tls.getSocketFactory());connection.setHostnameVerifier((name,session)->true);
  connection.setConnectTimeout(5000);connection.setReadTimeout(20000);connection.setRequestMethod(method);
  if(token!=null&&!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
  return connection;
 }
 private static JSONObject request(String host,String pin,String token,String method,String path,JSONObject body)throws Exception{
  HttpsURLConnection connection=open(host,pin,token,method,path);
  if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
   byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
   try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
  }
  int code=connection.getResponseCode();try(InputStream input=code>=400?connection.getErrorStream():connection.getInputStream()){
   if(input==null)throw new java.io.IOException("PCから応答がありません");
   ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
   while((n=input.read(buffer))!=-1){output.write(buffer,0,n);if(output.size()>64*1024*1024)throw new java.io.IOException("同期データが大きすぎます");}
   JSONObject result=new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
   if(code>=400)throw new SyncException(code,result.optString("error","同期エラー"));return result;
  }finally{connection.disconnect();}
 }
 static final class SyncException extends Exception{final int code;SyncException(int code,String message){super(message);this.code=code;}}
 static void pair(Context context,String text)throws Exception{
  Connection c=parseConnection(text);
  JSONObject response=request(c.host,c.fingerprint,null,"GET","/v1/pair?code="+c.code,null);
  Store.prefs(context).edit().putString("syncHost",c.host).putString("syncPin",c.fingerprint).putString("syncToken",response.getString("token")).apply();
 }
 static boolean isPaired(Context context){return !Store.prefs(context).getString("syncToken","").isEmpty();}
 static String sync(Context context)throws Exception{
  if(!syncInProgress.compareAndSet(false,true))return "同期中です";
  try{return syncOnce(context);}finally{syncInProgress.set(false);}
 }
 private static String syncOnce(Context context)throws Exception{
  SharedPreferences prefs=Store.prefs(context);String host=prefs.getString("syncHost","");String pin=prefs.getString("syncPin","");String token=prefs.getString("syncToken","");
  if(host.isEmpty()||token.isEmpty())return "PCと未接続です";
  try(BookStore store=new BookStore(context)){
   Map<String,SyncBookCodec.State> local=SyncBookCodec.states(store);Map<String,Integer> remote=new HashMap<>();
   JSONArray index=request(host,pin,token,"GET","/v1/books",null).getJSONArray("books");
   for(int i=0;i<index.length();i++){JSONObject item=index.getJSONObject(i);remote.put(item.getString("uuid"),item.getInt("revision"));}
   int uploaded=0,downloaded=0,conflicts=0;
   for(SyncBookCodec.State state:local.values()){
    BookStore.Book localBook=store.getBook(state.id);
    // Reserve the UUID even while capture is active. Otherwise the remote-only pass
    // below downloads an older snapshot over the book being captured.
    if(localBook!=null&&"capturing".equals(localBook.state)){remote.remove(state.uuid);continue;}
    Integer serverRevision=remote.remove(state.uuid);
    if(serverRevision==null){JSONObject snapshot=SyncBookCodec.exportBook(store,state.id);
     int revision=request(host,pin,token,"PUT","/v1/books/"+state.uuid,new JSONObject().put("base_revision",0).put("book",snapshot)).getInt("revision");
     SyncBookCodec.markSynced(store,state.id,revision,snapshot.getLong("updated_at"));uploaded++;continue;
    }
    if(state.dirty()){
     if(!state.contentDirty){
      try{JSONObject progress=new JSONObject().put("base_revision",state.revision).put("read_seq",localBook.readSeq).put("read_offset",localBook.readOffset);
       JSONObject answer=request(host,pin,token,"PUT","/v1/progress/"+state.uuid,progress);
       SyncBookCodec.markSynced(store,state.id,answer.getInt("revision"),state.updatedAt);uploaded++;
       if(answer.optBoolean("content_changed")){
        JSONObject fetched=request(host,pin,token,"GET","/v1/books/"+state.uuid,null);
        SyncBookCodec.importBook(store,fetched.getJSONObject("book"),fetched.getInt("revision"));downloaded++;
       }
       continue;
      }catch(SyncException ex){if(ex.code!=409)throw ex;}
     }
     JSONObject snapshot=SyncBookCodec.exportBook(store,state.id);
     try{int revision=request(host,pin,token,"PUT","/v1/books/"+state.uuid,new JSONObject().put("base_revision",state.revision).put("book",snapshot)).getInt("revision");
      SyncBookCodec.markSynced(store,state.id,revision,snapshot.getLong("updated_at"));uploaded++;continue;
     }catch(SyncException ex){if(ex.code!=409)throw ex;serverRevision=-1;}
    }
    if(serverRevision!=state.revision){
     if(state.dirty()){
      long copyId=SyncBookCodec.saveConflictCopy(store,state.id);
      String copyUuid=SyncBookCodec.exportBook(store,copyId).getString("uuid");
      prefs.edit().putString("syncConflict:"+copyUuid,state.uuid).apply();
      conflicts++;
     }
     JSONObject fetched=request(host,pin,token,"GET","/v1/books/"+state.uuid,null);
     SyncBookCodec.importBook(store,fetched.getJSONObject("book"),fetched.getInt("revision"));downloaded++;
    }
   }
   for(String uuid:remote.keySet()){
    JSONObject fetched=request(host,pin,token,"GET","/v1/books/"+uuid,null);
    SyncBookCodec.importBook(store,fetched.getJSONObject("book"),fetched.getInt("revision"));downloaded++;
   }
   return "同期完了：PCへ"+uploaded+"冊、Galaxyへ"+downloaded+"冊"+(conflicts>0?"。競合した本を"+conflicts+"冊保存しました":"");
  }
 }
}
