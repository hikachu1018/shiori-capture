package jp.shiori.capture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Best-effort chapter suggestions; users can correct boundaries in the editor. */
final class ChapterDetector {
 private static final Pattern HEADING=Pattern.compile("^(?:第[一二三四五六七八九十百千0-9０-９]+[章節部編]|(?:Chapter|CHAPTER|Part|PART)\\s*[0-9０-９IVXivx]+|序章|終章|はじめに|おわりに)(?:[ 　:：・.．].*)?$");
 private static String tocTitle(String line){return line.trim().replaceAll("[.．・…\\s　]+[0-9０-９]+$","").trim();}
 static List<BookStore.Chapter> detect(List<BookStore.Paragraph> paragraphs){
  List<BookStore.Chapter> chapters=new ArrayList<>();
  chapters.add(new BookStore.Chapter(0,"冒頭"));
  Set<String> titles=new HashSet<>();
  int start=0;
  while(start<paragraphs.size()){
   int end=start+1,screen=paragraphs.get(start).screen;
   while(end<paragraphs.size()&&paragraphs.get(end).screen==screen)end++;
   int candidateCount=0;boolean contents=false;
   for(int i=start;i<end;i++){
    String line=paragraphs.get(i).text.trim();
    if(line.equals("目次"))contents=true;
    if(line.length()<=60&&HEADING.matcher(line).matches())candidateCount++;
   }
   boolean indexPage=contents||candidateCount>=3;
   if(indexPage){
    for(int i=start;i<end;i++){
     String title=tocTitle(paragraphs.get(i).text);
     if(title.length()>=2&&title.length()<=60&&!title.equals("目次"))titles.add(title);
    }
   }else{
    for(int i=start;i<end;i++){
     BookStore.Paragraph p=paragraphs.get(i);String line=p.text.trim();
     if(line.length()>60||!(HEADING.matcher(line).matches()||titles.contains(line)))continue;
     BookStore.Chapter last=chapters.get(chapters.size()-1);
     if(p.seq==last.startSeq)chapters.set(chapters.size()-1,new BookStore.Chapter(last.startSeq,line));
     else chapters.add(new BookStore.Chapter(p.seq,line));
    }
   }
   start=end;
  }
  return chapters;
 }
}
