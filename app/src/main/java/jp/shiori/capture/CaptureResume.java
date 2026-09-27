package jp.shiori.capture;

/** Avoid saving the last captured page twice after a durable pause. */
final class CaptureResume {
 static boolean sameText(String previous,String current){
  if(previous==null||current==null)return false;
  String a=previous.replaceAll("\\s+","");
  String b=current.replaceAll("\\s+","");
  return a.codePointCount(0,a.length())>=8&&a.equals(b);
 }
 private CaptureResume(){}
}
