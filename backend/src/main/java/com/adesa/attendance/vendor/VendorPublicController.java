package com.adesa.attendance.vendor;
import java.util.*;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/v1/vendor/public")
public class VendorPublicController {
 private final VendorService service;private final String key;private final boolean approved;
 public VendorPublicController(VendorService service,@Value("${vendor.gateway-key:}")String key,@Value("${vendor.management-approved:false}")boolean approved){this.service=service;this.key=key;this.approved=approved;}
 private String guard(HttpServletRequest request,int limit){service.available();if(!approved&&!service.isDemo())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Vendor acquisition is awaiting management approval.");String sent=request.getHeader("X-Vendor-Gateway");if(key.length()<24||sent==null||!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),sent.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A valid vendor gateway is required.");String site=request.getHeader("X-Vendor-Site");VendorRules.check(VendorRules.SITES.contains(site),"Unknown registration website.");service.rate(request.getRequestURI()+":"+request.getHeader("X-Vendor-Client"),limit);return site;}
 private String session(HttpServletRequest r){String a=r.getHeader("Authorization");return a!=null&&a.startsWith("Bearer ")?a.substring(7):"";}
 @PostMapping("/open") public Map<String,Object> open(@RequestBody Map<String,Object> b,HttpServletRequest r){String site=guard(r,30);return service.open(VendorRules.text(b.get("token"),100),site,Boolean.TRUE.equals(b.get("correction")));}
 @GetMapping("/session") public Map<String,Object> state(HttpServletRequest r){return service.state(session(r),guard(r,180));}
 @PostMapping("/uploads") public Map<String,Object> upload(@RequestBody Map<String,Object>b,HttpServletRequest r){return service.uploadStart(session(r),guard(r,60),b);}
 @PostMapping("/uploads/{id}/chunk") public Map<String,Object> chunk(@PathVariable UUID id,@RequestBody Map<String,Object>b,HttpServletRequest r){return service.chunk(session(r),guard(r,360),id,b);}
 @PostMapping("/uploads/{id}/finish") public Map<String,Object> finish(@PathVariable UUID id,HttpServletRequest r){return service.finalizeUpload(session(r),guard(r,60),id);}
 @PostMapping("/uploads/{id}/remove") public void remove(@PathVariable UUID id,HttpServletRequest r){service.removeUpload(session(r),guard(r,60),id);}
 @PostMapping("/submit") public Map<String,Object> submit(@RequestBody Map<String,Object>b,HttpServletRequest r){return service.submit(session(r),guard(r,10),b);}
}
