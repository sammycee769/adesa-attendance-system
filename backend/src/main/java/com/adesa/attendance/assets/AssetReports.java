package com.adesa.attendance.assets;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Uses existing OpenPDF. CSV cells neutralize spreadsheet formula prefixes. */
final class AssetReports {
 private AssetReports() {}
 static byte[] csv(java.util.List<Map<String,Object>> rows) {
  if(rows.isEmpty()) return "No records\r\n".getBytes(StandardCharsets.UTF_8);
  var keys=rows.getFirst().keySet(); StringBuilder out=new StringBuilder();
  out.append(keys.stream().map(AssetReports::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
  for(var row:rows) out.append(keys.stream().map(k->cell(row.get(k))).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
  return out.toString().getBytes(StandardCharsets.UTF_8);
 }
 private static String cell(Object raw) { String s=Objects.toString(raw,""); if(!s.isEmpty() && "=+@-\t\r\n".indexOf(s.stripLeading().isEmpty()?' ':s.stripLeading().charAt(0))>=0) s="'"+s; return "\""+s.replace("\"","\"\"")+"\""; }
 static byte[] pdf(String title,java.util.List<Map<String,Object>> rows) {
  var bytes=new ByteArrayOutputStream(); Document document=new Document(PageSize.A4.rotate(),24,24,24,24);
  PdfWriter.getInstance(document,bytes); document.open(); document.add(new Paragraph("ADESA HQ - "+title)); document.add(new Paragraph("Generated: "+java.time.Instant.now())); document.add(new Paragraph(" "));
  if(rows.isEmpty()) document.add(new Paragraph("No records"));
  else { var keys=rows.getFirst().keySet(); var table=new PdfPTable(keys.size()); table.setWidthPercentage(100); table.setHeaderRows(1);
   for(String key:keys) table.addCell(new Phrase(key.replace('_',' '),FontFactory.getFont(FontFactory.HELVETICA,9,Font.BOLD)));
   for(var row:rows) for(String key:keys) table.addCell(new Phrase(Objects.toString(row.get(key),"—"),FontFactory.getFont(FontFactory.HELVETICA,8)));
   document.add(table);
  }
  document.close(); return bytes.toByteArray();
 }
 static byte[] clearance(Map<String,Object> c) {
  java.util.List<Map<String,Object>> lines=new ArrayList<>();
  for(String key:java.util.List.of("full_name","email","employee_number","subsidiary_name","status","clearance_date","officer","notes","outstandingReturns","unresolvedIssues")) lines.add(Map.of("Field",key,"Value",Objects.toString(c.get(key),"—")));
  for(String key:java.util.List.of("assets","outstandingAssets")) {
   Object value=c.get(key); if(value instanceof java.util.List<?> rows) for(Object row:rows) lines.add(Map.of("Field",key,"Value",row.toString()));
  }
  return pdf("Employee asset clearance",lines);
 }
}
