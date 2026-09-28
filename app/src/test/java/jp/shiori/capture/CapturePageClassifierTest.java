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
}
