package jp.shiori.capture;

/** Keeps sparse title pages and visually rich illustration pages as images. */
final class CapturePageClassifier {
 private CapturePageClassifier(){}
 static String imageKind(int screen,String text,double inkFraction){
  String trimmed=text.trim();String[] lines=trimmed.isEmpty()?new String[0]:trimmed.split("\\n");
  int characters=trimmed.replaceAll("\\s","").length();
  boolean contents=trimmed.contains("目次")||trimmed.toLowerCase(java.util.Locale.ROOT).contains("contents");
  boolean chapterHeading=trimmed.matches("(?s).*(?:第[一二三四五六七八九十0-9０-９]+[章節部編]|[Cc]hapter\\s*[0-9０-９]+).*?");
  boolean sparse=lines.length<=5&&characters<=100&&!trimmed.matches("(?s).*[。！？].*");
  if(screen<=2&&!contents&&!chapterHeading&&sparse&&inkFraction>=.01)return "cover";
  if(!contents&&inkFraction>=.16&&characters<=120&&lines.length<=12)return "illustration";
  return null;
 }
 static String title(String imageKind,String text){
  if(!"cover".equals(imageKind))return null;
  String best=null;
  for(String raw:text.split("\\n")){
   String line=raw.trim().replaceAll("[\u3000\\s]+"," ");
   if(line.length()<2||line.length()>70||line.matches(".*[。！？!?]$")||line.contains("目次")||line.matches(".*(?:著|作者|出版|発行).*"))continue;
   if(best==null||line.length()>best.length())best=line;
  }
  return best;
 }
}
