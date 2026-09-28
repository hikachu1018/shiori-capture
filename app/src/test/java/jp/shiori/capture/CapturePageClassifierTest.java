package jp.shiori.capture;

import static org.junit.Assert.*;
import org.junit.Test;

public class CapturePageClassifierTest {
 @Test public void keepsCoverAndSuggestsItsTitle(){
  String kind=CapturePageClassifier.imageKind(1,"しおりの物語\n山田 太郎",.04);
  assertEquals("cover",kind);
  assertEquals("しおりの物語",CapturePageClassifier.title(kind,"しおりの物語\n山田 太郎"));
 }
 @Test public void keepsIllustrationWithoutTreatingContentsAsCover(){
  assertEquals("illustration",CapturePageClassifier.imageKind(12,"挿絵",.31));
  assertNull(CapturePageClassifier.imageKind(1,"目次\n第一章 はじまり\n第二章 続き",.08));
  assertNull(CapturePageClassifier.imageKind(8,"長い本文です。\n次の行です。",.05));
 }
 @Test public void recognizesSparseDarkTitlePageFromReportedCapture(){
  String ocr="サピエンス全史\n一文明の構造と人類の幸福\nユザアルノア・ハラリ\n柴田裕之訳す\n河出書房新社";
  assertTrue(CapturePageClassifier.looksLikeCoverText(2,ocr));
  assertEquals("cover",CapturePageClassifier.imageKind(2,ocr,.00562));
  assertEquals("サピエンス全史",CapturePageClassifier.title("cover",ocr));
 }
 @Test public void doesNotHideSparseBodyAsTitlePage(){
  String body="はじめに\n本書では、人類の歩みを考える。\n文明の変化をたどる。";
  assertFalse(CapturePageClassifier.looksLikeCoverText(2,body));
  assertNull(CapturePageClassifier.imageKind(2,body,.00562));
 }
}
