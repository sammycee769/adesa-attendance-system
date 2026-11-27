package com.adesa.attendance.campaign;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers(disabledWithoutDocker=true) class CampaignIntegrationTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate db;
    CampaignService s;
    CampaignAdmin admin;
    TransactionTemplate tx;
    UUID event;
    String slug;
    @BeforeEach void setup()throws Exception{
        var ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
        db=new JdbcTemplate(ds);
        db.execute("drop schema if exists campaign_private cascade");
        try(var c=ds.getConnection()){
            try(var statement=c.createStatement()){statement.execute(new String(new ClassPathResource("db/migration/V21__events_leads_campaign.sql").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        }
        s=new CampaignService(db,new ObjectMapper());
        admin=new CampaignAdmin(s);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        event=UUID.randomUUID();
        slug="integration-test";
        db.update("insert into campaign_private.event(id,slug,name,description,starts_at,ends_at,active,approved,demo,config) values(?,?,?,'test',now()-interval '1 day',now()+interval '1 day',true,true,true,?::jsonb)",event,slug,"Integration event",s.encode(CampaignLocalDemo.defaultConfig()));
    }
    <T>T transaction(Supplier<T> fn){
        return tx.execute(status->fn.get());
    }
    String participant(int number){
        return String.valueOf(transaction(()->s.register(slug,Map.of("fullName","Test Participant "+number,"email","person"+number+"@example.test","phone","+23480"+String.format("%08d",number),"interest","ALL","termsAccepted",true,"termsVersion","event-v1"))).get("session"));
    }
    UUID prize(int count){
        UUID id=UUID.randomUUID();
        db.update("insert into campaign_private.prize(id,event_id,business,name,category,value_ngn,quantity,approved,active,terms) values(?,?,'ENERGY','NGN 10,000 first conversion voucher','PREMIUM',10000,?,true,true,'TEST ONLY')",id,event,count);
        return id;
    }
    @Test void registrationNormalisesAndRequiresConsent(){
        participant(1);
        assertThatThrownBy(()->transaction(()->s.register(slug,Map.of("fullName","Another","email"," PERSON1@EXAMPLE.TEST ","phone","+2348011111111","interest","ALL","termsAccepted",true,"termsVersion","event-v1")))).hasMessageContaining("already registered");
        assertThat(db.queryForObject("select marketing_consent from campaign_private.participant",Boolean.class)).isFalse();
        assertThatThrownBy(()->transaction(()->s.register(slug,Map.of("fullName","Another","email","new@example.test","phone","+2348011111111","interest","ALL","termsAccepted",false,"termsVersion","event-v1")))).hasMessageContaining("Accept");
    }
    @Test void concurrentAllocationNeverExceedsInventoryAndRetryCannotGiveSecondAttempt()throws Exception{
        UUID prize=prize(1);
        String a=participant(1),b=participant(2);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var gate=new CountDownLatch(1);
            var tasks=List.of(a,b).stream().map(token->pool.submit(()->{
                gate.await();try{
                    return transaction(()->s.begin(token,Map.of("game","SPIN")));
                }
                catch(RuntimeException e){
                    return Map.of("unavailable",true);
                }
            }
            )).toList();
            gate.countDown();
            for(var task:tasks)task.get(15,TimeUnit.SECONDS);
            assertThat(db.queryForObject("select issued from campaign_private.prize where id=?",Integer.class,prize)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from campaign_private.voucher",Integer.class)).isEqualTo(1);
            String winner=db.queryForObject("select session_hash from campaign_private.participant where id=(select participant_id from campaign_private.voucher)",String.class);
            String token=CampaignRules.hash(a).equals(winner)?a:b;
            var initial=transaction(()->s.state(token));
            var retried=transaction(()->s.begin(token,Map.of("game","SCRATCH")));
            assertThat(retried.get("attempt")).isEqualTo(initial.get("attempt"));
            assertThat(db.queryForObject("select count(*) from campaign_private.attempt",Integer.class)).isEqualTo(1);
        }
        finally{
            pool.shutdownNow();
        }
    }
    @Test void redemptionIsSingleUseAndSubsidiaryChecked(){
        prize(1);
        var state=transaction(()->s.begin(participant(1),Map.of("game","SCRATCH")));
        String code=String.valueOf(((Map<?,?>)state.get("voucher")).get("code"));
        assertThatThrownBy(()->transaction(()->admin.redeem(code,"MEDIA","TX1","staff@example.test"))).hasMessageContaining("different subsidiary");
        transaction(()->admin.redeem(code,"ENERGY","TX1","staff@example.test"));
        assertThatThrownBy(()->transaction(()->admin.redeem(code,"ENERGY","TX2","staff@example.test"))).hasMessageContaining("redeemed or expired");
        assertThat(db.queryForObject("select count(*) from campaign_private.audit where action='REDEEM'",Integer.class)).isEqualTo(1);
    }
    @Test void hiddenTilesAnswersAndTimerCannotBeSubmittedByClient(){
        String token=participant(1);
        var state=transaction(()->s.begin(token,Map.of("game","MATCH","score",6)));
        var a=(Map<?,?>)state.get("attempt");
        assertThat((List<?>)a.get("faces")).containsOnlyNulls();
        assertThat(a.containsKey("deck")).isFalse();
        assertThatThrownBy(()->transaction(()->s.action(token,Map.of("sequence",5,"type","flip","index",0)))).hasMessageContaining("stale");
        db.update("update campaign_private.attempt set deadline=now()-interval '1 second'");
        var ended=transaction(()->s.action(token,Map.of("sequence",0,"type","flip","index",0,"pairs",6)));
        assertThat(((Map<?,?>)ended.get("attempt")).get("status")).isEqualTo("CHOOSING");
        assertThat(((Map<?,?>)ended.get("attempt")).get("tier")).isEqualTo("GIFT");
    }
    @Test void recoveryRotatesCredentialsAndCannotBeReused(){
        String token=participant(1);
        transaction(()->{
            s.requestRecovery(slug,Map.of("email","person1@example.test","phone","+2348000000001"));return null;
        }
        );
        String body=db.queryForObject("select body from campaign_private.message where kind='RECOVERY'",String.class);
        assertThat(body).isNotNull();
        String recovery=body.split("recover=")[1].split("\\s")[0];
        String next=String.valueOf(transaction(()->s.recover(recovery)).get("session"));
        assertThatThrownBy(()->transaction(()->s.state(token))).hasMessageContaining("expired");
        assertThat(transaction(()->s.state(next)).get("participant")).isNotNull();
        assertThatThrownBy(()->transaction(()->s.recover(recovery))).hasMessageContaining("expired");
    }
}
