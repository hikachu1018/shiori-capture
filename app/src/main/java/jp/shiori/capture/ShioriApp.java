package jp.shiori.capture;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sync only while at least one app screen is visible. */
public final class ShioriApp extends Application implements Application.ActivityLifecycleCallbacks {
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
 private final AtomicBoolean busy=new AtomicBoolean();private volatile int visible;
 @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);
  timer.execute(()->{try(BookStore store=new BookStore(this)){store.purgeExpiredDeleted();}catch(Exception ignored){}});
  timer.scheduleWithFixedDelay(()->{if(visible<=0||!SyncClient.isPaired(this)||CaptureService.running||!busy.compareAndSet(false,true))return;
   try{String result=SyncClient.sync(this);Store.prefs(this).edit().putString("syncStatus",result).apply();}
   catch(Exception e){Store.prefs(this).edit().putString("syncStatus","同期待ち: "+e.getMessage()).apply();}
   finally{busy.set(false);}
  },5,15,TimeUnit.SECONDS);
 }
 @Override public void onActivityStarted(Activity activity){visible++;}
 @Override public void onActivityStopped(Activity activity){visible=Math.max(0,visible-1);}
 @Override public void onActivityCreated(Activity activity,Bundle state){}
 @Override public void onActivityResumed(Activity activity){}
 @Override public void onActivityPaused(Activity activity){}
 @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
 @Override public void onActivityDestroyed(Activity activity){}
}
