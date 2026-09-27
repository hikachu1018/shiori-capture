package jp.shiori.capture;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class BookArchiveTest {
 private static void entry(ZipOutputStream zip,String name,String value)throws Exception{
  zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
 }
 @Test public void importsOwnTwoChapterEpub()throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(ZipOutputStream zip=new ZipOutputStream(bytes)){
   entry(zip,"OEBPS/nav.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><nav><ol><li><a href='c0001.xhtml'>第一章</a></li><li><a href='c0002.xhtml'>第二章</a></li></ol></nav></body></html>");
   entry(zip,"OEBPS/c0001.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><p>最初の本文</p></body></html>");
   entry(zip,"OEBPS/c0002.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><p>次の本文</p></body></html>");
  }
  BookArchive.Imported book=BookArchive.importOldEpub(new ByteArrayInputStream(bytes.toByteArray()),"試験-章別.epub");
  assertEquals("試験",book.title);
  assertEquals(2,book.chapters.size());
  assertEquals("第二章",book.chapters.get(1).title);
  assertEquals("次の本文",book.chapters.get(1).paragraphs.get(0));
 }
 @Test(expected=java.io.IOException.class) public void rejectsPathTraversal()throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(ZipOutputStream zip=new ZipOutputStream(bytes)){entry(zip,"../nav.xhtml","bad");}
  BookArchive.importOldEpub(new ByteArrayInputStream(bytes.toByteArray()),"bad.epub");
 }
 @Test(expected=java.io.IOException.class) public void rejectsXmlDoctype()throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(ZipOutputStream zip=new ZipOutputStream(bytes)){
   entry(zip,"OEBPS/nav.xhtml","<!DOCTYPE html><html xmlns='http://www.w3.org/1999/xhtml'><body><a href='c0001.xhtml'>章</a></body></html>");
   entry(zip,"OEBPS/c0001.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><p>本文</p></body></html>");
  }
  BookArchive.importOldEpub(new ByteArrayInputStream(bytes.toByteArray()),"bad.epub");
 }
}
