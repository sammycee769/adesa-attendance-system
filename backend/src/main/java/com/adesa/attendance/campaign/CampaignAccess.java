package com.adesa.attendance.campaign;
import org.springframework.stereotype.Component;
import org.springframework.security.core.Authentication;
import com.adesa.attendance.security.AuthenticatedEmployee;
@Component("campaignAccess") public class CampaignAccess {
    private final CampaignService service;
    public CampaignAccess(CampaignService service){
        this.service=service;
    }
    public boolean allowed(Authentication auth,String capability){
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof AuthenticatedEmployee p))return false;
        if(auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")))return true;
        var roles=service.jdbc().queryForList("select capability from campaign_private.staff where email=?",p.email().toLowerCase(java.util.Locale.ROOT));
        return roles.stream().anyMatch(r->r.get("capability").equals("MANAGE")||capability.equals("VIEW")||r.get("capability").equals(capability));
    }
}
