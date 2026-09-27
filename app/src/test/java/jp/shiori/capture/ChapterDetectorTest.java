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
  assertEquals(3,chapters.size());
  assertEquals(0,chapters.get(0).startSeq);
  assertEquals(3,chapters.get(1).startSeq);
  assertEquals("第一章 はじまり",chapters.get(1).title);
  assertEquals(5,chapters.get(2).startSeq);
 }
 @Test public void leavesPlainTextAsOneChapter(){
  List<BookStore.Chapter> chapters=ChapterDetector.detect(Arrays.asList(
   new BookStore.Paragraph(0,1,"本文だけ"),new BookStore.Paragraph(1,1,"次の行")));
  assertEquals(1,chapters.size());
 }
}
