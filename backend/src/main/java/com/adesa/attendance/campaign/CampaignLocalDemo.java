package com.adesa.attendance.campaign;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.adesa.attendance.employee.*;
@Configuration @Profile("campaign-local") class CampaignLocalDemo {
    static final String URL="jdbc:postgresql://127.0.0.1:55441/adesa_campaign_demo";
    @Bean FlywayMigrationStrategy campaignMigrations(Environment env){
        if(Arrays.asList(env.getActiveProfiles()).contains("prod")||!URL.equals(env.getProperty("spring.datasource.url"))||env.getProperty("campaign.mail.enabled",Boolean.class,false)||env.getProperty("campaign.mail.live-approved",Boolean.class,false)||!"smtp".equals(env.getProperty("adesa.mail.provider"))||!env.getProperty("adesa.mail.host","").isBlank())throw new IllegalStateException("campaign-local requires its dedicated loopback demo database and disabled live mail");
        return flyway->flyway.migrate();
    }
    @Bean ApplicationRunner campaignSeed(CampaignService s,EmployeeRepository employees,PasswordEncoder passwords,org.springframework.transaction.support.TransactionTemplate tx){
        return args->tx.executeWithoutResult(status->{
            if(employees.findByEmail("admin@campaign.example.test")==null)employees.saveAndFlush(new Employee("LOCAL-CAMPAIGN-ADMIN","Synthetic Campaign Manager","admin@campaign.example.test",passwords.encode("Campaign-Demo-Only-2026!"),EmployeeRole.ADMIN,true));
            if(employees.findByEmail("viewer@campaign.example.test")==null)employees.saveAndFlush(new Employee("LOCAL-CAMPAIGN-VIEWER","Synthetic Campaign Viewer","viewer@campaign.example.test",passwords.encode("Campaign-Demo-Only-2026!"),EmployeeRole.EMPLOYEE,true));
            s.jdbc().update("insert into campaign_private.staff values('viewer@campaign.example.test','VIEW') on conflict do nothing");
            if(!s.jdbc().queryForList("select id from campaign_private.event where slug='demo-business-gathering'").isEmpty())return;
            UUID id=UUID.randomUUID();
            Instant now=Instant.now();
            s.jdbc().update("insert into campaign_private.event(id,slug,name,description,starts_at,ends_at,active,approved,demo,config) values(?,?,?,?,?,?,true,true,true,?::jsonb)",id,"demo-business-gathering","ADESA Play, Connect & Win — Local Demo","Synthetic participants and test rewards only. Nothing can be redeemed against real purchases.",java.sql.Timestamp.from(now.minusSeconds(3600)),java.sql.Timestamp.from(now.plus(Duration.ofDays(7))),s.encode(defaultConfig()));
            CampaignCatalogue.seed(s,id,true);
            String sample=String.valueOf(s.register("demo-business-gathering",Map.of("fullName","Ada Demo Participant","email","ada.fixture@example.test","phone","+2348000000001","company","Synthetic Demo Company","interest","ALL","termsAccepted",true,"termsVersion","event-v1","marketingConsent",false)).get("session"));
            s.begin(sample,Map.of("game","SPIN"));
            s.register("demo-business-gathering",Map.of("fullName","Bola Demo Lead","email","bola.fixture@example.test","phone","+2348000000002","interest","MEDIA","termsAccepted",true,"termsVersion","event-v1","marketingConsent",true));
        }
        );
    }
    static Map<String,Object> q(String text,List<String> options,int correct,String source){
        return Map.of("text",text,"options",options,"correct",correct,"source",source,"approved",true);
    }
    static Map<String,Object> defaultConfig(){
        var c=new LinkedHashMap<String,Object>();
        c.put("games",List.of("SPIN","MATCH","SCRATCH","TRIVIA"));
        c.put("weights",Map.of("REGULAR",40,"CONSULTATION",25,"PREMIUM",20,"GIFT",10,"GRAND",5));
        c.put("validityDays",90);
        c.put("lowStockThreshold",3);
        c.put("termsVersion","event-v1");
        c.put("publicUrl","http://localhost:3000");
        c.put("notifyEmail","staff@campaign.example.test");
        c.put("terms","One official attempt per person per event. Rewards are single-use, non-transferable, subject to eligibility, approved availability and stated expiry. Personal data is used to administer participation and redemption. Future marketing requires separate optional consent. Demo rewards have no real value.");
        c.put("questions",List.of( q("Which ADESA business focuses on CNG for mobility?",List.of("ADESA Energy","ADESA Media","ADESA Services","All three businesses"),0,"Management brief: ADESA Energy scope"), q("Which business offers an advertising consultation?",List.of("ADESA Services","ADESA Media","ADESA Energy","None of these"),1,"Management brief: Media prize catalogue"), q("Which business offers an initial service consultation?",List.of("ADESA Energy","ADESA Media","ADESA Services","None of these"),2,"Management brief: Services prize catalogue"), q("What does CNG stand for?",List.of("Compressed Natural Gas","Carbon Neutral Grid","Converted Network Gas","Combined National Grid"),0,"Approved ADESA Energy copy: Compressed Natural Gas (CNG)"), q("How many official prize-winning attempts are allowed per person per event?",List.of("One per game","Unlimited attempts","Two attempts","One across all games"),3,"Management brief: one official attempt per event") ));
        return c;
    }
}
