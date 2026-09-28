package jp.shiori.capture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes Kindle's chapter-time footer without changing the surrounding paragraph positions. */
final class KindleProgressFilter {
 private static final Pattern FOOTER=Pattern.compile(
  "章\\s*を\\s*読み\\s*終え\\s*る\\s*まで\\s*[:：]?\\s*[0-9０-９]+\\s*分\\s*[（(]?\\s*[0-9０-９]+\\s*[%％]\\s*[）)]?");

 private KindleProgressFilter(){}

 static String cleanText(String text){
  return String.join("\n",cleanLines(Arrays.asList(text.split("\\n",-1)))).trim();
 }

 /** Keeps one result per input line so existing database sequence numbers remain stable. */
 static List<String> cleanLines(List<String> lines){
  String joined=String.join("\n",lines);
  StringBuilder cleaned=new StringBuilder(joined);
  Matcher matches=FOOTER.matcher(joined);
  while(matches.find())for(int i=matches.start();i<matches.end();i++)if(cleaned.charAt(i)!='\n')cleaned.setCharAt(i,' ');
  String[] parts=cleaned.toString().split("\\n",-1);
  List<String> result=new ArrayList<>(parts.length);
  for(int i=0;i<parts.length;i++)result.add(parts[i].equals(lines.get(i))?lines.get(i):parts[i].trim());
  return result;
 }
}
