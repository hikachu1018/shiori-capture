package jp.shiori.capture;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small, shared visual system for the app's native screens. */
final class Ui {
 static final int BACKGROUND=0xfff4f7f5;
 static final int SURFACE=0xffffffff;
 static final int INK=0xff18313a;
 static final int MUTED=0xff5b7077;
 static final int GREEN=0xff146f60;
 static final int GREEN_SOFT=0xffe1f2ed;
 static final int BORDER=0xffd9e4e1;
 static final int WARNING=0xff865a19;
 static final int WARNING_SOFT=0xfffff2d9;
 static final int PRIMARY=1,SECONDARY=2,PLAIN=3;

 private Ui(){}
 static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
 static GradientDrawable background(Context c,int fill,int radius,int stroke){
  GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));
  if(stroke!=0)d.setStroke(dp(c,1),stroke);
  return d;
 }
 static TextView text(Context c,String value,int size,int color,boolean bold){
  TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);
  if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
  return v;
 }
 static LinearLayout card(Context c){
  LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);
  v.setPadding(dp(c,18),dp(c,17),dp(c,18),dp(c,17));
  v.setBackground(background(c,SURFACE,18,BORDER));
  return v;
 }
 static Button button(Context c,String label,int style,Runnable action){
  Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(15);
  b.setTypeface(Typeface.DEFAULT,style==PRIMARY?Typeface.BOLD:Typeface.NORMAL);
  b.setMinHeight(dp(c,50));b.setMinimumHeight(dp(c,50));
  b.setPadding(dp(c,12),0,dp(c,12),0);b.setStateListAnimator(null);
  int fill=style==PRIMARY?GREEN:style==SECONDARY?SURFACE:BACKGROUND;
  int stroke=style==SECONDARY?BORDER:0;
  b.setBackground(background(c,fill,13,stroke));
  b.setTextColor(style==PRIMARY?SURFACE:style==SECONDARY?GREEN:INK);
  b.setOnClickListener(v->action.run());return b;
 }
 static void gap(LinearLayout parent,Context c,int height){
  View spacer=new View(c);parent.addView(spacer,new LinearLayout.LayoutParams(1,dp(c,height)));
 }
 static LinearLayout.LayoutParams margins(Context c,int top,int bottom){
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
  p.topMargin=dp(c,top);p.bottomMargin=dp(c,bottom);return p;
 }
}
