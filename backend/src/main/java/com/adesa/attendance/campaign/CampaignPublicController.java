package com.adesa.attendance.campaign;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/v1/campaign/public") public class CampaignPublicController {
    final CampaignService s;
    final String key;
    final boolean enabled;
    public CampaignPublicController(CampaignService s,@Value("${campaign.gateway-key:}") String key,@Value("${campaign.enabled:false}") boolean enabled){
        this.s=s;
        this.key=key;
        this.enabled=enabled;
    }
    void guard(HttpServletRequest req,int limit){
        if(!enabled||key.length()<24)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Campaign participation is not enabled.");
        String sent=req.getHeader("X-Campaign-Gateway");
        if(sent==null||!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),sent.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Campaign gateway required.");
        String identity=req.getHeader("X-Campaign-Client");
        s.rate("ip:"+req.getRequestURI()+":"+(identity==null?req.getRemoteAddr():identity),limit);
    }
    String session(HttpServletRequest r){
        String token=r.getHeader("Authorization");
        return token!=null&&token.startsWith("Bearer ")?token.substring(7):null;
    }
    @GetMapping("/event/{slug}") public Map<String,Object> event(@PathVariable String slug,HttpServletRequest r){
        guard(r,1200);
        return s.publicEvent(slug);
    }
    @PostMapping("/event/{slug}/register") public Map<String,Object> register(@PathVariable String slug,@RequestBody Map<String,Object> body,HttpServletRequest r){
        guard(r,60);
        return s.register(slug,body);
    }
    @PostMapping("/event/{slug}/recovery") public Map<String,Object> recovery(@PathVariable String slug,@RequestBody Map<String,Object> body,HttpServletRequest r){
        guard(r,5);
        s.requestRecovery(slug,body);
        return Map.of("message","If the details match a registration, a secure recovery email is available. Demo emails are captured in HQ–Home.");
    }
    @PostMapping("/recover") public Map<String,Object> recover(@RequestBody Map<String,Object> body,HttpServletRequest r){
        guard(r,10);
        return s.recover(CampaignRules.text(body.get("token"),100));
    }
    @GetMapping("/state") public Map<String,Object> state(HttpServletRequest r){
        guard(r,1800);
        return s.state(session(r));
    }
    @PostMapping("/begin") public Map<String,Object> begin(@RequestBody Map<String,Object> body,HttpServletRequest r){
        guard(r,300);
        return s.begin(session(r),body);
    }
    @PostMapping("/action") public Map<String,Object> action(@RequestBody Map<String,Object> body,HttpServletRequest r){
        guard(r,1800);
        s.rate("session:"+CampaignRules.hash(String.valueOf(session(r))),180);
        return s.action(session(r),body);
    }
    @PostMapping("/choose") public Map<String,Object> choose(@RequestBody Map<String,Object> body,HttpServletRequest r){
        guard(r,300);
        return s.choose(session(r),body);
    }
}
