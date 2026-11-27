package com.adesa.attendance.vendor;
import static com.adesa.attendance.vendor.VendorRules.*;
import java.awt.*;import java.awt.geom.AffineTransform;import java.awt.image.BufferedImage;import java.io.*;import java.util.*;import javax.imageio.*;import com.lowagie.text.pdf.*;
public final class VendorFiles {
 private VendorFiles(){}
 public record Validated(byte[] bytes,String mime){}
 public static Validated validate(byte[] body,String filename,boolean signature){
 check(body.length>0&&body.length<=(signature?2:10)*1024*1024,"File exceeds its allowed size.");String lower=filename.toLowerCase(Locale.ROOT);
 if(body.length>=5&&new String(body,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")){
  check(!signature&&lower.endsWith(".pdf"),"Use PNG or JPG for signatures; document extensions must match their contents.");
  try(var reader=new PdfReader(body)){check(!reader.isEncrypted()&&reader.getNumberOfPages()>0&&reader.getNumberOfPages()<=500&&reader.getXrefSize()<=50000,"Use an unencrypted, standard PDF of at most 500 pages.");
   Set<PdfObject> visited=Collections.newSetFromMap(new IdentityHashMap<>());int[] count={0};
   for(int i=1;i<reader.getXrefSize();i++)inspect(reader.getPdfObject(i),visited,0,count);
  }catch(org.springframework.web.server.ResponseStatusException e){throw e;}catch(Exception e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"The PDF could not be validated.");}
  return new Validated(body,"application/pdf");
 }
 try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(body))){var readers=ImageIO.getImageReaders(input);check(readers.hasNext(),"Upload a valid PDF, PNG or JPG file.");var reader=readers.next();try{reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);check(Set.of("png","jpeg","jpg").contains(format),"Only PNG and JPG images are supported.");check(format.equals("png")?lower.endsWith(".png"):lower.endsWith(".jpg")||lower.endsWith(".jpeg"),"The filename must match its image contents.");int width=reader.getWidth(0),height=reader.getHeight(0);check(width>0&&height>0&&(long)width*height<=(signature?4000000:20000000),"Image dimensions are too large.");check(reader.read(0)!=null,"Image could not be decoded.");return new Validated(body,format.equals("png")?"image/png":"image/jpeg");}finally{reader.dispose();}}
 catch(org.springframework.web.server.ResponseStatusException e){throw e;}catch(Exception e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"The image could not be validated.");}
 }
 private static void inspect(PdfObject value,Set<PdfObject> visited,int depth,int[] count){
  if(value==null)return;check(depth<=50&&count[0]++<150000,"PDF structure is too complex.");
  PdfObject object=PdfReader.getPdfObject(value);if(object==null||!visited.add(object))return;
  Set<String> forbidden=Set.of("JavaScript","JS","Launch","EmbeddedFiles","EmbeddedFile","RichMedia","XFA","OpenAction","AA");
  if(object instanceof PdfName name)check(!forbidden.contains(PdfName.decodeName(name.toString())),"Interactive scripts and embedded files are not permitted in vendor PDFs.");
  if(object instanceof PdfDictionary dictionary)for(PdfName key:dictionary.getKeys()){check(!forbidden.contains(PdfName.decodeName(key.toString())),"Interactive scripts and embedded files are not permitted in vendor PDFs.");inspect(dictionary.get(key),visited,depth+1,count);}
  else if(object instanceof PdfArray array)for(int i=0;i<array.size();i++)inspect(array.getPdfObject(i),visited,depth+1,count);
 }
 public static byte[] signature(String name,String style){
 check(!name.isBlank()&&name.length()<=150,"Use a signatory name of at most 150 characters.");check(Set.of("CLASSIC","FLOW","BOLD").contains(style),"Select a signature style.");
 try(var input=VendorFiles.class.getResourceAsStream("/vendor/signature.otf")){if(input==null)throw new IllegalStateException();java.awt.Font font=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,input);BufferedImage image=new BufferedImage(1200,300,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(17,36,63));float size=110;java.awt.Font chosen=font.deriveFont(style.equals("BOLD")?java.awt.Font.BOLD:java.awt.Font.PLAIN,size);while(g.getFontMetrics(chosen).stringWidth(name)>1060&&size>18)chosen=font.deriveFont(style.equals("BOLD")?java.awt.Font.BOLD:java.awt.Font.PLAIN,--size);g.setFont(chosen);if(style.equals("FLOW"))g.transform(new AffineTransform(1,0,-.16,1,65,0));g.drawString(name,60,190);if(style.equals("BOLD")){g.drawString(name,61,190);g.setStroke(new BasicStroke(2));g.drawLine(65,212,Math.min(1110,65+g.getFontMetrics().stringWidth(name)),212);}}finally{g.dispose();}ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();}catch(Exception e){throw new IllegalStateException("Signature rendering is unavailable",e);}
 }
}
