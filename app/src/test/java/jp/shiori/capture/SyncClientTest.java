package jp.shiori.capture;

import static org.junit.Assert.*;
import org.junit.Test;

public class SyncClientTest {
 @Test public void connectionTextRequiresHostFingerprintAndOneTimeCode(){
  String pin="a".repeat(64);
  SyncClient.Connection connection=SyncClient.parseConnection("192.168.1.2:8765|"+pin+"|123456");
  assertEquals("192.168.1.2:8765",connection.host);
  assertEquals(pin,connection.fingerprint);
  assertEquals("123456",connection.code);
 }
 @Test public void rejectsMalformedConnectionText(){
  for(String value:new String[]{"192.168.1.2:8765|123456","example.com:8765|"+"a".repeat(64)+"|12345","https://example.com:8765|"+"a".repeat(64)+"|123456"}){
   try{SyncClient.parseConnection(value);fail(value);}catch(IllegalArgumentException expected){}
  }
 }
}
