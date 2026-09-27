package jp.shiori.capture;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
final class Store {
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("reader",Context.MODE_PRIVATE);}
 static void status(Context c,String value){prefs(c).edit().putString("status",value).apply();}
 static synchronized void addFile(Context c,String name,String uri){
  try{JSONArray a=new JSONArray(prefs(c).getString("files","[]"));JSONObject o=new JSONObject();o.put("name",name);o.put("uri",uri);a.put(o);while(a.length()>100)a.remove(0);prefs(c).edit().putString("files",a.toString()).commit();}catch(Exception ignored){}
 }
}
