package jp.shiori.capture;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class PhraseTokenizerTest {
 @Test public void phrasesKeepOrderAndOriginalOffsets(){
  String source="これは日本語の文章です。 次の文も読みます！";
  List<PhraseTokenizer.Token> tokens=PhraseTokenizer.segment(source);
  assertTrue(tokens.size()>2);
  StringBuilder combined=new StringBuilder();
  for(PhraseTokenizer.Token token:tokens){
   assertEquals(token.text.charAt(0),source.charAt(token.offset));
   assertTrue(token.text.codePointCount(0,token.text.length())<=9);
   combined.append(token.text);
  }
  assertEquals(source.replace(" ",""),combined.toString());
 }
 @Test public void surrogatePairsAreNeverSplit(){
  String source="日本語😀の読書体験";
  for(PhraseTokenizer.Token token:PhraseTokenizer.segment(source)){
   assertFalse(Character.isLowSurrogate(token.text.charAt(0)));
   assertFalse(Character.isHighSurrogate(token.text.charAt(token.text.length()-1)));
  }
 }
}
