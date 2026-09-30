package jp.shiori.capture;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds chapter starts in the body and uses the captured table of contents for their names. */
final class ChapterDetector {
 static final class Analysis {
  final List<BookStore.Chapter> chapters;
  final Set<Integer> hiddenSeqs;
  final List<String> tocTitles;
  final int tocCount,matchedCount;
  Analysis(List<BookStore.Chapter> chapters,Set<Integer> hiddenSeqs,List<String> tocTitles,int matchedCount){
   this.chapters=chapters;this.hiddenSeqs=hiddenSeqs;this.tocTitles=tocTitles;this.tocCount=tocTitles.size();this.matchedCount=matchedCount;
  }
 }
 private static final class Screen {
  final List<BookStore.Paragraph> lines=new ArrayList<>();
  final int number;
  Screen(int number){this.number=number;}
 }
 private static final Pattern HEADING=Pattern.compile("^(?:第[一二三四五六七八九十百千0-9０-９]+[章節部編]|(?:Chapter|CHAPTER|Part|PART)\\s*[0-9０-９IVXivx]+|序章|終章|はじめに|おわりに)(?:[ 　:：・.．].*)?$");
 private static final Pattern BARE_HEADING=Pattern.compile("^(?:第[一二三四五六七八九十百千0-9０-９]+[章節部編]|(?:Chapter|CHAPTER|Part|PART)\\s*[0-9０-９IVXivx]+)$");
 private static final Pattern ORDINAL=Pattern.compile("第([0-9]+)章");
 private static final Pattern JAPANESE_ORDINAL=Pattern.compile("第([一二三四五六七八九十]+)章");

 private ChapterDetector(){}
 private static String tocTitle(String line){return line.trim().replaceAll("[.．・…\\s　]+[0-9０-９]+$","").trim();}
 private static boolean tocHeader(String line){String value=Normalizer.normalize(line.trim(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);return value.equals("目次")||value.equals("もくじ")||value.equals("contents");}
 private static String key(String text){
  String value=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  Matcher m=JAPANESE_ORDINAL.matcher(value);StringBuffer out=new StringBuffer();
  while(m.find())m.appendReplacement(out,"第"+kanjiNumber(m.group(1))+"章");m.appendTail(out);
  return out.toString().replaceAll("[\\s\\p{Punct}・。．、：　…]","");
 }
 private static int kanjiNumber(String text){
  int result=0,current=0;
  for(int i=0;i<text.length();i++){
   int digit="一二三四五六七八九".indexOf(text.charAt(i));
   if(digit>=0)current=digit+1;
   else if(text.charAt(i)=='十'){result+=(current==0?1:current)*10;current=0;}
  }
  return result+current;
 }
 private static int ordinal(String text){Matcher m=ORDINAL.matcher(key(text));return m.find()?Integer.parseInt(m.group(1)):-1;}
 private static int distance(String a,String b,int limit){
  if(Math.abs(a.length()-b.length())>limit)return limit+1;
  int[] previous=new int[b.length()+1];for(int j=0;j<previous.length;j++)previous[j]=j;
  for(int i=1;i<=a.length();i++){
   int[] current=new int[b.length()+1];current[0]=i;int best=current[0];
   for(int j=1;j<=b.length();j++){current[j]=Math.min(Math.min(previous[j]+1,current[j-1]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));best=Math.min(best,current[j]);}
   if(best>limit)return limit+1;previous=current;
  }
  return previous[b.length()];
 }
 private static boolean matches(String entry,String candidate){
  String a=key(entry),b=key(candidate);if(a.isEmpty()||b.isEmpty())return false;
  if(a.equals(b))return true;
  String heading=candidate.trim();
  if(heading.length()>65||heading.matches(".*[。！？!?、].*"))return false;
  int first=ordinal(entry),second=ordinal(candidate);
  if(first>=0&&first==second&&HEADING.matcher(heading).matches())return true;
  if(b.startsWith(a)&&b.length()>a.length()
   &&b.substring(a.length()).matches("^(?:は|が|を|に|で|と|も|です|ます|でした|だった).*"))return false;
  if(Math.min(a.length(),b.length())>=4&&(a.contains(b)||b.contains(a))
   &&Math.abs(a.length()-b.length())<=Math.max(4,Math.min(a.length(),b.length())/3))return true;
  int limit=Math.max(a.length(),b.length())>=9?2:1;
  return Math.min(a.length(),b.length())>=5&&distance(a,b,limit)<=limit;
 }
 private static List<Screen> screens(List<BookStore.Paragraph> paragraphs){
  List<Screen> result=new ArrayList<>();
  for(BookStore.Paragraph p:paragraphs){
   if(result.isEmpty()||result.get(result.size()-1).number!=p.screen)result.add(new Screen(p.screen));
   result.get(result.size()-1).lines.add(p);
  }
  return result;
 }
 private static int tocHeaderAt(Screen screen){for(int i=0;i<screen.lines.size();i++)if(tocHeader(screen.lines.get(i).text))return i;return -1;}
 private static boolean looksLikeToc(Screen screen){
  int shortLines=0,headings=0;
  for(BookStore.Paragraph p:screen.lines){String line=p.text.trim();if(line.length()<=65)shortLines++;if(HEADING.matcher(tocTitle(line)).matches())headings++;}
  return headings>=3&&shortLines==screen.lines.size();
 }
 private static void addEntries(Screen screen,int from,List<String> entries){
  for(int i=from;i<screen.lines.size();i++){
   String title=tocTitle(screen.lines.get(i).text);
   if(tocHeader(title)||title.length()<2||title.length()>65)continue;
   if(BARE_HEADING.matcher(title).matches()&&i+1<screen.lines.size()){
    String next=tocTitle(screen.lines.get(i+1).text);
    if(next.length()>=2&&next.length()<=50&&!HEADING.matcher(next).matches()&&!tocHeader(next)){title=title+" "+next;i++;}
   }
   if(entries.isEmpty()||!key(entries.get(entries.size()-1)).equals(key(title)))entries.add(title);
  }
 }
 private static int matchingEntry(List<String> entries,int from,Screen screen){
  for(int i=from;i<entries.size();i++)if(matchingLine(entries.get(i),screen)>=0)return i;
  return -1;
 }
 private static int matchingLine(String entry,Screen screen){
  int first=Math.min(4,screen.lines.size());
  for(int j=0;j<first;j++){
   if(matches(entry,screen.lines.get(j).text))return j;
   if(j+1<first&&matches(entry,screen.lines.get(j).text+" "+screen.lines.get(j+1).text))return j;
  }
  return -1;
 }
 static List<BookStore.Chapter> detect(List<BookStore.Paragraph> paragraphs){return analyze(paragraphs).chapters;}
 static Analysis analyze(List<BookStore.Paragraph> paragraphs){
  List<BookStore.Chapter> chapters=new ArrayList<>();chapters.add(new BookStore.Chapter(0,"冒頭"));
  Set<Integer> hidden=new HashSet<>();List<Screen> pages=screens(paragraphs);if(pages.isEmpty())return new Analysis(chapters,hidden,new ArrayList<>(),0);
  int tocStart=-1,header=-1;
  for(int i=0;i<Math.min(pages.size(),15);i++){
   int at=tocHeaderAt(pages.get(i));
   if(at>=0||looksLikeToc(pages.get(i))){tocStart=i;header=at;break;}
  }
  List<String> entries=new ArrayList<>();int tocEnd=tocStart;
  if(tocStart>=0){
   addEntries(pages.get(tocStart),header<0?0:header+1,entries);
   for(int i=tocStart+1;i<pages.size();i++){
    Screen page=pages.get(i);int shortLines=0,headings=0;
    for(BookStore.Paragraph p:page.lines){String s=p.text.trim();if(s.length()<=65)shortLines++;if(HEADING.matcher(tocTitle(s)).matches())headings++;}
    if(matchingEntry(entries,0,page)>=0)break;
    boolean continuation=page.lines.size()>=2&&shortLines==page.lines.size()&&(headings>=2||page.lines.size()>=3);
    if(!continuation)break;
    addEntries(page,0,entries);tocEnd=i;
   }
   {
    for(int i=tocStart;i<=tocEnd;i++)for(BookStore.Paragraph p:pages.get(i).lines)hidden.add(p.seq);
    for(int i=tocStart-1;i>=0&&i>=tocStart-2;i--){
     Screen page=pages.get(i);int length=0;for(BookStore.Paragraph p:page.lines)length+=p.text.length();
     if(page.lines.size()>2||length>60)break;
     for(BookStore.Paragraph p:page.lines)hidden.add(p.seq);
    }
   }
  }
  int nextEntry=0,matched=0;boolean visibleBefore=false;
  for(int i=0;i<pages.size();i++){
   Screen page=pages.get(i);if(hidden.contains(page.lines.get(0).seq))continue;
   int found=entries.isEmpty()?-1:matchingEntry(entries,nextEntry,page);
   if(found>=0){
    int line=matchingLine(entries.get(found),page);BookStore.Paragraph p=page.lines.get(Math.max(0,line));
    if(!visibleBefore)chapters.set(0,new BookStore.Chapter(0,entries.get(found)));
    else if(p.seq>chapters.get(chapters.size()-1).startSeq)chapters.add(new BookStore.Chapter(p.seq,entries.get(found)));
    nextEntry=found+1;matched++;visibleBefore=true;continue;
   }
   if(entries.isEmpty())for(int j=0;j<Math.min(3,page.lines.size());j++){
    BookStore.Paragraph p=page.lines.get(j);String line=p.text.trim();
    if(line.length()>65||!HEADING.matcher(line).matches())continue;
    if(!visibleBefore)chapters.set(0,new BookStore.Chapter(0,line));
    else if(p.seq>chapters.get(chapters.size()-1).startSeq)chapters.add(new BookStore.Chapter(p.seq,line));
    break;
   }
   visibleBefore=true;
  }
  return new Analysis(chapters,hidden,entries,matched);
 }
}
