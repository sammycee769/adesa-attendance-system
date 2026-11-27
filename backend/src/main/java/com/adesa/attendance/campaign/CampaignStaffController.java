package com.adesa.attendance.campaign;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.adesa.attendance.security.AuthenticatedEmployee;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/campaign/staff") @PreAuthorize("@campaignAccess.allowed(authentication,'VIEW')") public class CampaignStaffController {
    final CampaignAdmin admin;
    final CampaignService s;
    final CampaignAccess access;
    public CampaignStaffController(CampaignAdmin admin,CampaignService s,CampaignAccess access){
        this.admin=admin;
        this.s=s;
        this.access=access;
    }
    String actor(Authentication auth){
        return AuthenticatedEmployee.from(auth).email();
    }
    @GetMapping("/access") public Map<String,Object> access(Authentication auth){
        return Map.of("view",true,"manage",access.allowed(auth,"MANAGE"),"redeem",access.allowed(auth,"REDEEM"));
    }
    @GetMapping("/events") public List<Map<String,Object>> events(){
        return admin.events();
    }
    @PostMapping("/events") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public Map<String,Object> create(@RequestBody Map<String,Object> body,Authentication auth){
        return Map.of("id",admin.saveEvent(null,body,actor(auth)));
    }
    @PutMapping("/events/{id}") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public void save(@PathVariable UUID id,@RequestBody Map<String,Object> body,Authentication auth){
        admin.saveEvent(id,body,actor(auth));
    }
    @GetMapping("/events/{id}/dashboard") public Map<String,Object> dashboard(@PathVariable UUID id){
        return admin.dashboard(id);
    }
    @GetMapping("/events/{id}/participants") public List<Map<String,Object>> participants(@PathVariable UUID id){
        return admin.participants(id);
    }
    @PostMapping("/events/{id}/prizes") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public Map<String,Object> prize(@PathVariable UUID id,@RequestBody Map<String,Object> body,Authentication auth){
        return Map.of("id",admin.savePrize(id,null,body,actor(auth)));
    }
    @PutMapping("/events/{id}/prizes/{prize}") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public void prizeEdit(@PathVariable UUID id,@PathVariable UUID prize,@RequestBody Map<String,Object> body,Authentication auth){
        admin.savePrize(id,prize,body,actor(auth));
    }
    @GetMapping("/voucher/{code}") public Map<String,Object> voucher(@PathVariable String code){
        return admin.voucher(code,false);
    }
    @PostMapping("/voucher/{code}/redeem") @PreAuthorize("@campaignAccess.allowed(authentication,'REDEEM')") public Map<String,Object> redeem(@PathVariable String code,@RequestBody Map<String,Object> body,Authentication auth){
        return admin.redeem(code,CampaignRules.text(body.get("business"),12),CampaignRules.text(body.get("transaction"),180),actor(auth));
    }
    @PutMapping("/participants/{id}/conversion") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public void conversion(@PathVariable UUID id,@RequestBody Map<String,Object> body,Authentication auth){
        admin.conversion(id,Boolean.TRUE.equals(body.get("converted")),CampaignRules.text(body.get("reference"),180),actor(auth));
    }
    @GetMapping("/events/{id}/audit") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public List<Map<String,Object>> audit(@PathVariable UUID id){
        return s.jdbc().queryForList("select * from campaign_private.audit where event_id=? order by id desc limit 500",id);
    }
    @GetMapping("/events/{id}/messages") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public List<Map<String,Object>> messages(@PathVariable UUID id){
        return s.jdbc().queryForList("select m.id,m.kind,m.subject,m.state,m.created_at,case when e.demo then m.body else 'Body available only in isolated demonstrations' end as body,case when e.demo then m.recipient else '' end as recipient from campaign_private.message m join campaign_private.event e on e.id=m.event_id where m.event_id=? order by m.created_at desc limit 200",id);
    }
    @PostMapping("/maintenance") @PreAuthorize("@campaignAccess.allowed(authentication,'MANAGE')") public void maintain(){
        admin.maintain();
    }
    @PutMapping("/staff") @PreAuthorize("hasRole('ADMIN')") public void staff(@RequestBody Map<String,Object> body,Authentication auth){
        String email=CampaignRules.email(CampaignRules.text(body.get("email"),254)),cap=CampaignRules.text(body.get("capability"),12);
        CampaignRules.require(Set.of("MANAGE","REDEEM","VIEW","NONE").contains(cap),"Invalid campaign permission.");
        if(cap.equals("NONE"))s.jdbc().update("delete from campaign_private.staff where email=?",email);
        else s.jdbc().update("insert into campaign_private.staff values(?,?) on conflict(email) do update set capability=excluded.capability",email,cap);
        s.audit(null,actor(auth),"CAMPAIGN_STAFF",Map.of("email",email,"capability",cap));
    }
    @GetMapping(value="/events/{id}/export.csv",produces="text/csv") public ResponseEntity<String> export(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean marketingOnly){
        var rows=admin.participants(id).stream().filter(r->!marketingOnly||Boolean.TRUE.equals(r.get("marketing_consent"))).toList();
        var fields=List.of("full_name","email","phone","company","industry","interest","registered_at","marketing_consent","game","game_status","tier","moves","score","matched_pairs","reward","code","expires_at","redeemed_at","converted","transaction_ref");
        StringBuilder csv=new StringBuilder("\ufeff"+String.join(",",fields)+"\r\n");
        for(var row:rows){
            csv.append(String.join(",",fields.stream().map(f->cell(row.get(f))).toList())).append("\r\n");
        }
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=campaign-leads.csv").header("Cache-Control","no-store").body(csv.toString());
    }
    static String cell(Object value){
        String s=value==null?"":value.toString();
        if(s.matches("(?s)^[\\s]*[=+@-].*"))s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
}
