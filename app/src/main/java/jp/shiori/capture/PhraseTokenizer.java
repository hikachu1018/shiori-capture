package jp.shiori.capture;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stable UTF-16 offsets let reading progress survive speed and chapter edits. */
final class PhraseTokenizer {
 static final class Token{
  final int offset;final String text;
  Token(int offset,String text){this.offset=offset;this.text=text;}
 }
 private static boolean punctuation(String s){return s.matches("[。！？!?、，,．.；;：:）)］】」』]+");}
 private static int points(String s){return s.codePointCount(0,s.length());}
 private static void emit(List<Token> out,StringBuilder text,int offset){if(text.length()>0){out.add(new Token(offset,text.toString()));text.setLength(0);}}
 static List<Token> segment(String source){
  List<Token> out=new ArrayList<>();StringBuilder phrase=new StringBuilder();int phraseStart=0;
  BreakIterator words=BreakIterator.getWordInstance(Locale.JAPANESE);words.setText(source);
  for(int start=words.first(),end=words.next();end!=BreakIterator.DONE;start=end,end=words.next()){
   String part=source.substring(start,end);
   if(part.trim().isEmpty()){emit(out,phrase,phraseStart);continue;}
   if(punctuation(part)){
    if(phrase.length()==0)phraseStart=start;
    phrase.append(part);emit(out,phrase,phraseStart);continue;
   }
   int pos=start;
   while(pos<end){
    if(phrase.length()==0)phraseStart=pos;
    int room=8-points(phrase.toString());
    if(room<=0){emit(out,phrase,phraseStart);continue;}
    int take=Math.min(room,part.codePointCount(pos-start,end-start));
    int stop=source.offsetByCodePoints(pos,take);
    phrase.append(source,pos,stop);pos=stop;
    if(points(phrase.toString())>=8)emit(out,phrase,phraseStart);
   }
   if(phrase.length()>0&&points(phrase.toString())>=6)emit(out,phrase,phraseStart);
  }
  emit(out,phrase,phraseStart);
  return out;
 }
}
