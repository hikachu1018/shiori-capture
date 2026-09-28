package jp.shiori.capture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes Kindle's chapter-time footer without changing the surrounding paragraph positions. */
final class KindleProgressFilter {
 // OCR may omit the colon, time or percentage, so the stable label is the anchor.
 private static final Pattern LABEL=Pattern.compile("章\\s*を\\s*読\\s*み\\s*終\\s*え\\s*る\\s*ま\\s*で(?![にのが、。])");
 private static final Pattern CONTINUATION=Pattern.compile("^[\\s:：()（）.．・あと残り0-9０-９分%％]+$");

 private KindleProgressFilter(){}

 static boolean isFooterRegion(int lineTop,int screenHeight){return screenHeight>0&&lineTop>=screenHeight*.90f;}

 static String cleanText(String text){
  return String.join("\n",cleanLines(Arrays.asList(text.split("\\n",-1)))).trim();
 }

 /** Keeps one result per input line so existing database sequence numbers remain stable. */
 static List<String> cleanLines(List<String> lines){
  String joined=String.join("\n",lines);
  StringBuilder cleaned=new StringBuilder(joined);
  Matcher matches=LABEL.matcher(joined);
  while(matches.find()){
   int end=joined.indexOf('\n',matches.end());if(end<0)end=joined.length();
   int next=end;
   // A fragmented footer may put the time and percentage on separate OCR lines.
   for(int extra=0;extra<2&&next<joined.length();extra++){
    int start=next+1,stop=joined.indexOf('\n',start);if(stop<0)stop=joined.length();
    String fragment=joined.substring(start,stop).trim();
    if(fragment.isEmpty()||!CONTINUATION.matcher(fragment).matches())break;
    next=stop;
   }
   for(int i=matches.start();i<Math.max(end,next);i++)if(cleaned.charAt(i)!='\n')cleaned.setCharAt(i,' ');
  }
  String[] parts=cleaned.toString().split("\\n",-1);
  List<String> result=new ArrayList<>(parts.length);
  for(int i=0;i<parts.length;i++)result.add(parts[i].equals(lines.get(i))?lines.get(i):parts[i].trim());
  return result;
 }
}
