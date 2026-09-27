package jp.shiori.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Optional exports and a deliberately narrow importer for Capture 0.2 EPUBs. */
final class BookArchive {
 static final class ImportedChapter{
  final String title;final List<String> paragraphs;
  ImportedChapter(String title,List<String> paragraphs){this.title=title;this.paragraphs=paragraphs;}
 }
 static final class Imported{
  final String title;final List<ImportedChapter> chapters;
  Imported(String title,List<ImportedChapter> chapters){this.title=title;this.chapters=chapters;}
 }
 private static final class ChapterText{
  final String title;final List<String> paragraphs=new ArrayList<>();
  ChapterText(String title){this.title=title;}
 }
 private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
 private static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
 private static void entry(ZipOutputStream zip,String name,String value)throws IOException{
  zip.putNextEntry(new ZipEntry(name));zip.write(utf(value));zip.closeEntry();
 }
 private static Document parseXml(byte[] data)throws Exception{
  String source=new String(data,StandardCharsets.UTF_8);
  if(source.indexOf('\0')>=0||source.matches("(?is).*<!\\s*(?:DOCTYPE|ENTITY).*"))
   throw new IOException("EPUBのXML宣言を読み取れません");
  DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
  f.setExpandEntityReferences(false);
  return f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
 }
 static Imported importOldEpub(InputStream stream,String fallbackTitle)throws Exception{
  Map<String,byte[]> files=new HashMap<>();long total=0;int entries=0;
  try(ZipInputStream zip=new ZipInputStream(stream,StandardCharsets.UTF_8)){
   ZipEntry entry;
   while((entry=zip.getNextEntry())!=null){
    if(++entries>1000)throw new IOException("EPUBの項目数が多すぎます");
    String name=entry.getName();
    if(name.contains("..")||name.startsWith("/")||name.contains("\\"))throw new IOException("EPUBのパスが正しくありません");
    boolean needed=name.equals("OEBPS/nav.xhtml")||name.matches("OEBPS/c[0-9]{4}\\.xhtml");
    ByteArrayOutputStream data=needed?new ByteArrayOutputStream():null;byte[] buffer=new byte[8192];int n;
    while((n=zip.read(buffer))!=-1){total+=n;if(total>32L*1024*1024)throw new IOException("EPUBの本文が大きすぎます");if(needed)data.write(buffer,0,n);}
    if(needed)files.put(name,data.toByteArray());
    zip.closeEntry();
   }
  }
  if(!files.containsKey("OEBPS/nav.xhtml"))throw new IOException("旧版の章付きEPUBではありません");
  Document nav=parseXml(files.get("OEBPS/nav.xhtml"));
  NodeList links=nav.getElementsByTagNameNS("*","a");List<ImportedChapter> chapters=new ArrayList<>();
  for(int i=0;i<links.getLength();i++){
   Element link=(Element)links.item(i);String href=link.getAttribute("href");
   if(!href.matches("c[0-9]{4}\\.xhtml"))throw new IOException("旧版以外の章構成です");
   byte[] data=files.get("OEBPS/"+href);if(data==null)throw new IOException("章本文が見つかりません");
   String title=link.getTextContent().trim();if(title.isEmpty())title="章 "+(i+1);
   Document page=parseXml(data);NodeList nodes=page.getElementsByTagNameNS("*","p");List<String> paragraphs=new ArrayList<>();
   for(int j=0;j<nodes.getLength();j++){String line=nodes.item(j).getTextContent().trim();if(!line.isEmpty())paragraphs.add(line);}
   if(paragraphs.isEmpty())paragraphs.add("［本文がありません］");
   chapters.add(new ImportedChapter(title,paragraphs));
  }
  if(chapters.isEmpty())throw new IOException("章が見つかりません");
  String title=fallbackTitle.replaceFirst("(?i)-章別\\.epub$","").replaceFirst("(?i)\\.epub$","").trim();
  return new Imported(title.isEmpty()?"取り込んだ本":title,chapters);
 }
 private static List<ChapterText> content(BookStore db,long bookId){
  List<BookStore.Chapter> boundaries=db.listChapters(bookId);
  List<BookStore.Paragraph> paragraphs=db.listParagraphs(bookId);
  List<ChapterText> out=new ArrayList<>();if(boundaries.isEmpty())boundaries.add(new BookStore.Chapter(0,"冒頭"));
  int at=0;
  for(BookStore.Paragraph p:paragraphs){
   while(at+1<boundaries.size()&&p.seq>=boundaries.get(at+1).startSeq)at++;
   while(out.size()<=at)out.add(new ChapterText(boundaries.get(out.size()).title));
   out.get(at).paragraphs.add(p.text);
  }
  if(out.isEmpty())out.add(new ChapterText(boundaries.get(0).title));
  return out;
 }
 private static void writeEpub(OutputStream stream,String title,List<ChapterText> chapters)throws IOException{
  try(ZipOutputStream zip=new ZipOutputStream(stream,StandardCharsets.UTF_8)){
   byte[] mime=utf("application/epub+zip");CRC32 crc=new CRC32();crc.update(mime);
   ZipEntry first=new ZipEntry("mimetype");first.setMethod(ZipEntry.STORED);first.setSize(mime.length);first.setCompressedSize(mime.length);first.setCrc(crc.getValue());
   zip.putNextEntry(first);zip.write(mime);zip.closeEntry();
   entry(zip,"META-INF/container.xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
   entry(zip,"OEBPS/style.css","body{line-height:1.8;margin:5%;font-family:serif}h1{font-size:1.4em}p{margin:.35em 0}");
   StringBuilder manifest=new StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>");
   StringBuilder spine=new StringBuilder();
   StringBuilder nav=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"ja\"><head><title>目次</title></head><body><nav epub:type=\"toc\" id=\"toc\"><h1>目次</h1><ol>");
   for(int i=0;i<chapters.size();i++){
    String id=String.format(Locale.US,"c%04d",i+1),file=id+".xhtml";ChapterText chapter=chapters.get(i);
    StringBuilder page=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"ja\"><head><title>").append(xml(chapter.title)).append("</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head><body><h1>").append(xml(chapter.title)).append("</h1>");
    for(String paragraph:chapter.paragraphs)page.append("<p>").append(xml(paragraph)).append("</p>");
    entry(zip,"OEBPS/"+file,page.append("</body></html>").toString());
    manifest.append("<item id=\"").append(id).append("\" href=\"").append(file).append("\" media-type=\"application/xhtml+xml\"/>");
    spine.append("<itemref idref=\"").append(id).append("\"/>");
    nav.append("<li><a href=\"").append(file).append("\">").append(xml(chapter.title)).append("</a></li>");
   }
   entry(zip,"OEBPS/nav.xhtml",nav.append("</ol></nav></body></html>").toString());
   SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);date.setTimeZone(TimeZone.getTimeZone("UTC"));
   entry(zip,"OEBPS/content.opf","<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\" xml:lang=\"ja\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">urn:uuid:"+UUID.randomUUID()+"</dc:identifier><dc:title>"+xml(title)+"</dc:title><dc:language>ja</dc:language><meta property=\"dcterms:modified\">"+date.format(new Date())+"</meta></metadata><manifest>"+manifest+"</manifest><spine>"+spine+"</spine></package>");
  }
 }
 private interface Writer{void write(OutputStream out)throws Exception;}
 private static Uri save(Context context,Uri parent,String mime,String name,Writer writer)throws Exception{
  Uri uri=DocumentsContract.createDocument(context.getContentResolver(),parent,mime,name);
  if(uri==null)throw new IOException("保存先に書き込めません");
  boolean ok=false;
  try(OutputStream out=context.getContentResolver().openOutputStream(uri,"w")){
   if(out==null)throw new IOException("保存先を開けません");
   writer.write(out);out.flush();ok=true;
  }finally{if(!ok)try{DocumentsContract.deleteDocument(context.getContentResolver(),uri);}catch(Exception ignored){}}
  return uri;
 }
 static String exportEpub(Context context,BookStore db,long bookId,Uri tree)throws Exception{
  BookStore.Book book=db.getBook(bookId);if(book==null)throw new IOException("本が見つかりません");
  Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
  String name=cleanName(book.title)+".epub";
  save(context,parent,"application/epub+zip",name,out->writeEpub(out,book.title,content(db,bookId)));
  return name;
 }
 static String exportPdf(Context context,BookStore db,long bookId,Uri tree)throws Exception{
  BookStore.Book book=db.getBook(bookId);if(book==null)throw new IOException("本が見つかりません");
  Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
  String name=cleanName(book.title)+".pdf";
  PdfDocument doc=new PdfDocument();
  try{
   Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(13);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
   PdfDocument.Page page=null;Canvas canvas=null;float y=0;
   List<ChapterText> chapters=content(db,bookId);
   for(ChapterText chapter:chapters){
    List<String> lines=new ArrayList<>();lines.add(chapter.title);lines.addAll(chapter.paragraphs);
    for(String paragraph:lines){
     String remaining=paragraph.trim();
     while(!remaining.isEmpty()){
      if(page==null||y>794){if(page!=null)doc.finishPage(page);page=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,doc.getPages().size()+1).create());canvas=page.getCanvas();canvas.drawColor(Color.WHITE);y=54;}
      int n=paint.breakText(remaining,true,507,null);if(n<1)n=1;
      if(n<remaining.length()&&Character.isHighSurrogate(remaining.charAt(n-1)))n--;
      if(n<1)n=Math.min(2,remaining.length());
      canvas.drawText(remaining.substring(0,n),44,y,paint);y+=22;remaining=remaining.substring(n);
     }
     y+=5;
    }
    y+=14;
   }
   if(page!=null)doc.finishPage(page);
   if(doc.getPages().isEmpty())throw new IOException("本文がありません");
   save(context,parent,"application/pdf",name,doc::writeTo);
  }finally{doc.close();}
  return name;
 }
 private static String cleanName(String title){
  String result=title.replaceAll("[\\\\/:*?\"<>|]","").trim();
  return result.isEmpty()?"しおりの本":result.length()>60?result.substring(0,60):result;
 }
}
