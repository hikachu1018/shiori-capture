package jp.shiori.capture;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

public final class KindleProgressFilterTest {
 @Test public void removesFooterButKeepsBodyOnSameLine(){
  assertEquals("物語は続く。",KindleProgressFilter.cleanText("物語は続く。 章を読み終えるまで: 7分 42％"));
 }

 @Test public void removesFullWidthAndSplitFooter(){
  assertEquals(Arrays.asList("本文", "", "", "次の段落"),KindleProgressFilter.cleanLines(Arrays.asList(
   "本文", "章を読み終えるまで：５分", "４２％", "次の段落")));
 }

 @Test public void leavesOrdinaryChapterTextUntouched(){
  assertEquals("章を読み終えるまでに時間がかかった。",KindleProgressFilter.cleanText("章を読み終えるまでに時間がかかった。"));
  assertEquals(Arrays.asList("  行頭の空白", "別の段落"),KindleProgressFilter.cleanLines(Arrays.asList("  行頭の空白", "別の段落")));
 }
}
