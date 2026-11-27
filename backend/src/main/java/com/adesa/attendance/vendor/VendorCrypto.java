package com.adesa.attendance.vendor;
import java.security.*;import java.nio.charset.StandardCharsets;import java.util.*;import javax.crypto.*;import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Component;
@Component
public final class VendorCrypto {
 private final byte[] key;private final SecureRandom random=new SecureRandom();
 public VendorCrypto(@Value("${vendor.encryption-key:}")String value){try{key=value.isBlank()?null:Base64.getDecoder().decode(value);if(key!=null&&key.length!=32)throw new IllegalArgumentException();}catch(Exception e){throw new IllegalStateException("Vendor encryption requires a base64-encoded 32-byte key");}}
 public boolean configured(){return key!=null;}
 public String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public String fingerprint(String value){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(("vendor-dedupe:"+value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Vendor key unavailable");}}
 public byte[] seal(byte[] plain,String purpose){try{if(key==null)throw new IllegalStateException();byte[] iv=new byte[12];random.nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));byte[] encrypted=c.doFinal(plain),out=new byte[13+encrypted.length];out[0]=1;System.arraycopy(iv,0,out,1,12);System.arraycopy(encrypted,0,out,13,encrypted.length);return out;}catch(Exception e){throw new IllegalStateException("Private vendor encryption failed");}}
 public byte[] open(byte[] value,String purpose){try{if(key==null||value.length<29||value[0]!=1)throw new IllegalStateException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(value,1,13)));c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return c.doFinal(Arrays.copyOfRange(value,13,value.length));}catch(Exception e){throw new IllegalStateException("Private vendor record could not be authenticated");}}
}
