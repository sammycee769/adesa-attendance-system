package com.adesa.attendance.vendor;
import java.util.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.web.bind.annotation.*;import org.springframework.http.*;
@RestController @RequestMapping("/api/v1/vendor/admin") @PreAuthorize("hasRole('ADMIN')")
public class VendorAdminController {
 private final VendorAdmin admin;
 public VendorAdminController(VendorAdmin admin){this.admin=admin;}
 @GetMapping("/overview") public Map<String,Object> overview(){return admin.overview();}
 @GetMapping("/links") public java.util.List<Map<String,Object>> links(){return admin.links();}
 @PostMapping("/links") public Map<String,Object> link(@RequestBody Map<String,Object>b){return admin.createLink(b);}
 @PutMapping("/links/{id}") public void linkState(@PathVariable UUID id,@RequestBody Map<String,Object>b){admin.linkState(id,VendorRules.text(b.get("state"),20));}
 @GetMapping("/applications/{id}") public Map<String,Object> profile(@PathVariable UUID id,@RequestParam(defaultValue="0")int revision){return admin.profile(id,revision);}
 @PutMapping("/applications/{id}/status") public void status(@PathVariable UUID id,@RequestBody Map<String,Object>b){admin.status(id,VendorRules.text(b.get("status"),40),VendorRules.text(b.get("reason"),5000));}
 @PutMapping("/applications/{id}/banking") public void banking(@PathVariable UUID id,@RequestBody Map<String,Object>b){admin.banking(id,VendorRules.text(b.get("status"),20),VendorRules.text(b.get("reason"),3000));}
 @PostMapping("/applications/{id}/notes") public void note(@PathVariable UUID id,@RequestBody Map<String,Object>b){admin.note(id,VendorRules.text(b.get("body"),5000));}
 @PostMapping("/applications/{id}/requests") public Map<String,Object> request(@PathVariable UUID id,@RequestBody Map<String,Object>b){VendorRules.check(b.get("days") instanceof Number,"Choose an expiry in days.");return admin.request(id,VendorRules.text(b.get("message"),5000),((Number)b.get("days")).intValue());}
 @PutMapping("/applications/{id}/related/{related}") public void relate(@PathVariable UUID id,@PathVariable UUID related){admin.associate(id,related);}
 @GetMapping("/notices") public java.util.List<Map<String,Object>> notices(){return admin.notices();}
 @PostMapping("/notices/{id}/read") public void read(@PathVariable UUID id){admin.readNotice(id);}
 @GetMapping("/mail-preview") public Map<String,Object> preview(){return admin.mailPreview();}
 @PostMapping("/documents/{id}/ticket") public Map<String,Object> ticket(@PathVariable UUID id){return admin.documentTicket(id);}
 @GetMapping("/documents/download/{ticket}") public ResponseEntity<byte[]> document(@PathVariable String ticket){var d=admin.download(ticket);String name=String.valueOf(d.get("filename")).replaceAll("[^A-Za-z0-9._ -]","_");return file((byte[])d.get("bytes"),String.valueOf(d.get("mime")),name);}
 @GetMapping("/applications/{id}/pdf") public ResponseEntity<byte[]> pdf(@PathVariable UUID id,@RequestParam(defaultValue="1")int revision){var profile=admin.profile(id,revision);var answers=(Map<?,?>)profile.get("answers");String name=String.valueOf(answers.get("businessName")).replaceAll("[^A-Za-z0-9_-]","_");if(name.length()>65)name=name.substring(0,65);return file(admin.pdf(id,revision),"application/pdf","Vendor_Application_"+name+"_"+profile.get("reference")+".pdf");}
 private ResponseEntity<byte[]> file(byte[] body,String type,String name){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=\""+name+"\"").contentType(MediaType.parseMediaType(type)).body(body);}
}
