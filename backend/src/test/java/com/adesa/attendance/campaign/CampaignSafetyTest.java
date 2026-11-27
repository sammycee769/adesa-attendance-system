package com.adesa.attendance.campaign;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CampaignSafetyTest {
    @Test void localProfileFailsBeforeMigrationOnProductionResources(){
        var config=new CampaignLocalDemo();
        var env=new MockEnvironment();
        assertThatThrownBy(()->config.campaignMigrations(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("spring.datasource.url",CampaignLocalDemo.URL).withProperty("adesa.mail.provider","smtp");
        assertThat(config.campaignMigrations(env)).isNotNull();
        env.withProperty("campaign.mail.enabled","true");
        assertThatThrownBy(()->config.campaignMigrations(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("campaign.mail.enabled","false").withProperty("adesa.mail.host","smtp.example.com");
        assertThatThrownBy(()->config.campaignMigrations(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("adesa.mail.host","").setActiveProfiles("campaign-local","prod");
        assertThatThrownBy(()->config.campaignMigrations(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void publicGatewayIsClosedByDefaultAndRejectsForgedKeys(){
        var service=mock(CampaignService.class);
        var request=new MockHttpServletRequest();
        assertThatThrownBy(()->new CampaignPublicController(service,"",false).guard(request,30)).hasMessageContaining("not enabled");
        var controller=new CampaignPublicController(service,"local-campaign-gateway-demo-only-2026",true);
        assertThatThrownBy(()->controller.guard(request,30)).hasMessageContaining("gateway required");
        request.addHeader("X-Campaign-Gateway","local-campaign-gateway-demo-only-2026");
        controller.guard(request,30);
        verify(service).rate(anyString(),eq(30));
    }
    @Test void staffPermissionsAreSeparateFromEmployeeRoles(){
        var db=mock(JdbcTemplate.class);
        var s=new CampaignService(db,new ObjectMapper());
        var access=new CampaignAccess(s);
        assertThat(access.allowed(null,"MANAGE")).isFalse();
        var principal=new AuthenticatedEmployee(UUID.randomUUID(),"viewer@example.test","Viewer",EmployeeRole.EMPLOYEE);
        var auth=new UsernamePasswordAuthenticationToken(principal,"",List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        when(db.queryForList(anyString(),eq("viewer@example.test"))).thenReturn(List.of(Map.of("capability","VIEW")));
        assertThat(access.allowed(auth,"VIEW")).isTrue();
        assertThat(access.allowed(auth,"MANAGE")).isFalse();
        assertThat(access.allowed(auth,"REDEEM")).isFalse();
        var admin=new UsernamePasswordAuthenticationToken(principal,"",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(access.allowed(admin,"MANAGE")).isTrue();
    }
    @Test void invalidRulesAndUnverifiedQuestionsCannotActivate(){
        var s=new CampaignService(mock(JdbcTemplate.class),new ObjectMapper());
        var admin=new CampaignAdmin(s);
        var c=CampaignLocalDemo.defaultConfig();
        admin.validateConfig(c);
        c.put("weights",Map.of("REGULAR",-1,"CONSULTATION",25,"PREMIUM",20,"GIFT",10,"GRAND",5));
        assertThatThrownBy(()->admin.validateConfig(c)).hasMessageContaining("probability");
        final var invalid=CampaignLocalDemo.defaultConfig();
        invalid.put("terms","Save 10%");
        assertThatThrownBy(()->admin.validateConfig(invalid)).hasMessageContaining("not permitted");
    }
}
