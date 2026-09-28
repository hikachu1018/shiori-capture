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

 @Test public void removesIncompleteOcrFooterAndItsFragments(){
  assertEquals(Arrays.asList("本文", "", "", "", "次の本文"),KindleProgressFilter.cleanLines(Arrays.asList(
   "本文", "章を読み終えるまで：", "７分", "４２％", "次の本文")));
  assertEquals("本文",KindleProgressFilter.cleanText("本文\n章 を 読 み 終 え る ま で : ○分 ○％"));
 }

 @Test public void ignoresLinesInTheBottomScreenStrip(){
  assertEquals(false,KindleProgressFilter.isFooterRegion(899,1000));
  assertEquals(true,KindleProgressFilter.isFooterRegion(900,1000));
 }

 @Test public void leavesOrdinaryChapterTextUntouched(){
  assertEquals("章を読み終えるまでに時間がかかった。",KindleProgressFilter.cleanText("章を読み終えるまでに時間がかかった。"));
  assertEquals(Arrays.asList("  行頭の空白", "別の段落"),KindleProgressFilter.cleanLines(Arrays.asList("  行頭の空白", "別の段落")));
 }
}
