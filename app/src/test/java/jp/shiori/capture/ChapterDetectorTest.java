package jp.shiori.capture;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ChapterDetectorTest {
 @Test public void detectsBodyHeadingsFromContentsPage(){
  List<BookStore.Paragraph> lines=Arrays.asList(
   new BookStore.Paragraph(0,1,"目次"),
   new BookStore.Paragraph(1,1,"第一章 はじまり 1"),
   new BookStore.Paragraph(2,1,"第二章 次の話 5"),
   new BookStore.Paragraph(3,2,"第一章 はじまり"),
   new BookStore.Paragraph(4,2,"本文一"),
   new BookStore.Paragraph(5,3,"第二章 次の話"),
   new BookStore.Paragraph(6,3,"本文二")
  );
  List<BookStore.Chapter> chapters=ChapterDetector.detect(lines);
  assertEquals(2,chapters.size());
  assertEquals(0,chapters.get(0).startSeq);
  assertEquals("第一章 はじまり",chapters.get(0).title);
  assertEquals(5,chapters.get(1).startSeq);
  assertEquals("第二章 次の話",chapters.get(1).title);
  assertTrue(ChapterDetector.analyze(lines).hiddenSeqs.contains(1));
 }
 @Test public void carriesContentsAcrossScreensAndMatchesNumberStyles(){
  List<BookStore.Paragraph> lines=Arrays.asList(
   new BookStore.Paragraph(0,1,"本の題名"),
   new BookStore.Paragraph(1,2,"目次"),
   new BookStore.Paragraph(2,2,"第一章 はじまり 1"),
   new BookStore.Paragraph(3,2,"第二章 続き 5"),
   new BookStore.Paragraph(4,3,"第三章 結び 9"),
   new BookStore.Paragraph(5,3,"第四章 その後 15"),
   new BookStore.Paragraph(6,4,"第1章"),
   new BookStore.Paragraph(7,4,"はじまり"),
   new BookStore.Paragraph(8,4,"最初の本文です。"),
   new BookStore.Paragraph(9,5,"第２章 続き"),
   new BookStore.Paragraph(10,5,"次の本文です。"),
   new BookStore.Paragraph(11,6,"第三章 結び"),
   new BookStore.Paragraph(12,6,"最後の本文です。")
  );
  ChapterDetector.Analysis result=ChapterDetector.analyze(lines);
  assertEquals(4,result.tocCount);
  assertEquals(3,result.matchedCount);
  assertEquals(3,result.chapters.size());
  assertEquals("第一章 はじまり",result.chapters.get(0).title);
  assertEquals("第二章 続き",result.chapters.get(1).title);
  assertEquals(9,result.chapters.get(1).startSeq);
  assertEquals("第三章 結び",result.chapters.get(2).title);
  for(int seq=0;seq<=5;seq++)assertTrue(result.hiddenSeqs.contains(seq));
  assertFalse(result.hiddenSeqs.contains(6));
 }
 @Test public void leavesPlainTextAsOneChapter(){
  List<BookStore.Chapter> chapters=ChapterDetector.detect(Arrays.asList(
   new BookStore.Paragraph(0,1,"本文だけ"),new BookStore.Paragraph(1,1,"次の行")));
  assertEquals(1,chapters.size());
 }
 @Test public void matchesUnnumberedTitlesAndExcludesAnIsolatedContentsScreen(){
  List<BookStore.Paragraph> contents=Arrays.asList(
   new BookStore.Paragraph(0,1,"目次"),new BookStore.Paragraph(1,1,"はじめに"),new BookStore.Paragraph(2,1,"遠い街の話"));
  assertTrue(ChapterDetector.analyze(contents).hiddenSeqs.contains(0));
  List<BookStore.Paragraph> complete=Arrays.asList(
   contents.get(0),contents.get(1),contents.get(2),
   new BookStore.Paragraph(3,2,"はじめに"),new BookStore.Paragraph(4,2,"最初の本文"),
   new BookStore.Paragraph(5,3,"遠い街の話"),new BookStore.Paragraph(6,3,"次の本文"));
  ChapterDetector.Analysis result=ChapterDetector.analyze(complete);
  assertEquals(2,result.matchedCount);
  assertEquals("遠い街の話",result.chapters.get(1).title);
  assertTrue(result.hiddenSeqs.contains(0));
 }
}
