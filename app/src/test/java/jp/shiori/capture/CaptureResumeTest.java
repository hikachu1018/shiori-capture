package jp.shiori.capture;

import static org.junit.Assert.*;
import org.junit.Test;

public class CaptureResumeTest {
 @Test public void skipsTheSameOcrPageAcrossLineBreakChanges(){
  assertTrue(CaptureResume.sameText("これは同じページの本文です。\n次の行です。","これは同じページの本文です。 次の行です。"));
 }
 @Test public void keepsTheNextPageAndShortGenericHeadings(){
  assertFalse(CaptureResume.sameText("前のページの本文です。","次のページの本文です。"));
  assertFalse(CaptureResume.sameText("第二章","第二章"));
 }
}
