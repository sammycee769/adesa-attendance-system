package com.adesa.attendance.campaign;
import static com.adesa.attendance.campaign.CampaignRules.*;
import static com.adesa.attendance.campaign.CampaignService.number;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional public class CampaignAdmin {
    final CampaignService s;
    public CampaignAdmin(CampaignService s){
        this.s=s;
    }
    public List<Map<String,Object>> events(){
        return s.jdbc().queryForList("select id,slug,name,description,starts_at,ends_at,active,approved,demo,config from campaign_private.event order by created_at desc").stream().map(e->{
            var x=new LinkedHashMap<>(e);x.put("config",s.object(e.get("config")));return (Map<String,Object>)x;
        }
        ).toList();
    }
    public UUID saveEvent(UUID id,Map<String,Object> input,String actor){
        String slug=text(input.get("slug"),80),name=text(input.get("name"),160);
        require(slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")&&!name.isBlank(),"Use a name and a lowercase event slug.");
        var start=Instant.parse(String.valueOf(input.get("starts_at")));
        var end=Instant.parse(String.valueOf(input.get("ends_at")));
        require(end.isAfter(start),"Event end must follow its start.");
        var config=s.object(s.encode(input.get("config")));
        validateConfig(config);
        boolean active=Boolean.TRUE.equals(input.get("active")),approved=Boolean.TRUE.equals(input.get("approved"));
        UUID event=id==null?UUID.randomUUID():id;
        if(id!=null){
            var old=s.event(id,true);
            require(slug.equals(old.get("slug")),"Event slugs cannot change after creation.");
            if(active&&Boolean.TRUE.equals(old.get("active")))throw conflict("Deactivate the event before editing its rules or dates.");
        }
        if(active){
            require(approved,"Management approval is required before activation.");
            require(!s.available(event,"ALL").isEmpty(),"Approve and activate prize inventory before opening an event.");
            var weights=s.weights(config);
            require(s.available(event,"ALL").stream().anyMatch(p->weights.get(String.valueOf(p.get("category")))>0),"Available prizes must have a positive category weight.");
            if(((List<?>)config.get("games")).contains("TRIVIA"))require(CampaignService.list(config.get("questions")).stream().filter(q->Boolean.TRUE.equals(q.get("approved"))).count()>=5,"Approve five verified trivia questions before activation.");
        }
        s.jdbc().update("insert into campaign_private.event(id,slug,name,description,starts_at,ends_at,active,approved,config) values(?,?,?,?,?,?,?,?,?::jsonb) on conflict(id) do update set name=excluded.name,description=excluded.description,starts_at=excluded.starts_at,ends_at=excluded.ends_at,active=excluded.active,approved=excluded.approved,config=excluded.config",event,slug,name,text(input.get("description"),1200),java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),active,approved,s.encode(config));
        if(id==null)CampaignCatalogue.seed(s,event,false);
        s.audit(event,actor,"EVENT_RULES",input);
        return event;
    }
    void validateConfig(Map<String,Object> c){
        require(c.get("games") instanceof List<?> games&&!games.isEmpty()&&GAMES.containsAll(games)&&games.size()==new HashSet<>(games).size(),"Select valid games.");
        int days=number(c.get("validityDays"));
        require(days>=1&&days<=365,"Voucher validity must be 1–365 days.");
        require(number(c.get("lowStockThreshold"))>=0,"Invalid inventory threshold.");
        require(!text(c.get("termsVersion"),40).isBlank(),"Terms version is required.");
        noDiscount(text(c.get("terms"),5000));
        String url=text(c.get("publicUrl"),500);
        require(url.matches("https://[^/]+")||url.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]+"),"Use an HTTPS website origin, or a loopback demo origin.");
        String notify=text(c.get("notifyEmail"),254);
        if(!notify.isBlank())email(notify);
        int total=0;
        for(int weight:s.weights(c).values()){
            require(weight>=0&&weight<=10000,"Invalid probability weight.");
            total+=weight;
        }
        require(total>0,"At least one category must have a positive probability weight.");
        require(c.get("questions") instanceof List<?>,"Questions must be an array.");
        for(var q:CampaignService.list(c.get("questions"))){
            require(!text(q.get("text"),300).isBlank()&&q.get("options") instanceof List<?> o&&o.size()==4,"Each question needs text and four options.");
            require(number(q.get("correct"))>=0&&number(q.get("correct"))<4,"Select a correct answer.");
            require(((List<?>)q.get("options")).stream().allMatch(option->option instanceof String&&!text(option,200).isBlank()),"Answer options cannot be blank.");
            require(!text(q.get("source"),500).isBlank(),"Record the verified source for each question.");
        }
    }
    public UUID savePrize(UUID event,UUID id,Map<String,Object> input,String actor){
        var e=s.event(event,true);
        require(!Boolean.TRUE.equals(e.get("active")),"Deactivate the event before changing prizes or inventory.");
        String business=text(input.get("business"),12),category=text(input.get("category"),16),name=text(input.get("name"),180),terms=text(input.get("terms"),3000);
        require(BUSINESSES.contains(business)&&CATEGORIES.contains(category)&&!name.isBlank(),"Choose a valid business, category and reward name.");
        noDiscount(name);
        noDiscount(terms);
        int quantity=number(input.get("quantity"));
        require(quantity>=0&&quantity<=100000,"Invalid prize quantity.");
        Long value=input.get("value_ngn")==null?null:((Number)input.get("value_ngn")).longValue();
        require(value==null||value>0&&value<=100000000,"Use a positive fixed NGN amount.");
        if(Boolean.TRUE.equals(input.get("approved"))||Boolean.TRUE.equals(input.get("active")))require(!(name.toLowerCase(Locale.ROOT).contains("voucher")||category.equals("GRAND"))||value!=null,"Approve a fixed NGN amount before enabling monetary vouchers or grand prizes.");
        UUID prize=id==null?UUID.randomUUID():id;
        if(id!=null){
            var old=s.one("select * from campaign_private.prize where id=? and event_id=?",id,event);
            require(quantity>=number(old.get("issued")),"Quantity cannot be lower than already issued rewards.");
            if(number(old.get("issued"))>0) require(business.equals(old.get("business"))&&category.equals(old.get("category"))&&name.equals(old.get("name"))&&terms.equals(old.get("terms"))&&Objects.equals(value,old.get("value_ngn")==null?null:((Number)old.get("value_ngn")).longValue()),"Issued reward descriptions and values are immutable. Only inventory and activation may change.");
        }
        s.jdbc().update("insert into campaign_private.prize(id,event_id,business,name,category,value_ngn,quantity,approved,active,terms) values(?,?,?,?,?,?,?,?,?,?) on conflict(id) do update set business=excluded.business,name=excluded.name,category=excluded.category,value_ngn=excluded.value_ngn,quantity=excluded.quantity,approved=excluded.approved,active=excluded.active,terms=excluded.terms",prize,event,business,name,category,value,quantity,Boolean.TRUE.equals(input.get("approved")),Boolean.TRUE.equals(input.get("active")),terms);
        s.audit(event,actor,"PRIZE_CHANGE",input);
        return prize;
    }
    public Map<String,Object> dashboard(UUID event){
        var result=new LinkedHashMap<String,Object>();
        result.put("event",s.publicEvent(String.valueOf(s.event(event,false).get("slug"))));
        result.put("registrations",s.jdbc().queryForObject("select count(*) from campaign_private.participant where event_id=?",Long.class,event));
        result.put("byBusiness",s.jdbc().queryForList("select interest,count(*) as count from campaign_private.participant where event_id=? group by interest",event));
        result.put("byGame",s.jdbc().queryForList("select a.game,count(*) as count from campaign_private.attempt a join campaign_private.participant p on p.id=a.participant_id where p.event_id=? group by a.game",event));
        result.put("prizes",s.jdbc().queryForList("select *,quantity-issued as remaining from campaign_private.prize where event_id=? order by business,category",event));
        result.put("vouchers",s.jdbc().queryForList("select count(*) as issued,count(*) filter(where redeemed_at is not null) as redeemed,count(*) filter(where redeemed_at is null and expires_at>now()) as unredeemed,count(*) filter(where redeemed_at is null and expires_at<=now()) as expired from campaign_private.voucher v join campaign_private.participant p on p.id=v.participant_id where p.event_id=?",event).getFirst());
        result.put("converted",s.jdbc().queryForObject("select count(*) from campaign_private.participant where event_id=? and converted",Long.class,event));
        result.put("messages",s.jdbc().queryForList("select id,kind,subject,state,created_at,delivered_at,attempts from campaign_private.message where event_id=? order by created_at desc limit 100",event));
        return result;
    }
    public List<Map<String,Object>> participants(UUID event){
        return s.jdbc().queryForList("select p.id,p.full_name,p.email,p.phone,p.company,p.industry,p.interest,p.registered_at,p.marketing_consent,p.converted,p.transaction_ref,a.game,a.status as game_status,a.tier,a.state->>'moves' as moves,a.state->>'score' as score,jsonb_array_length(coalesce(a.state->'matched','[]'::jsonb))/2 as matched_pairs,v.code,v.issued_at,v.expires_at,v.redeemed_at,v.redeemed_by,v.redemption_ref,v.reward_snapshot->>'name' as reward from campaign_private.participant p left join campaign_private.attempt a on a.participant_id=p.id left join campaign_private.voucher v on v.participant_id=p.id where p.event_id=? order by p.registered_at desc",event);
    }
    public Map<String,Object> voucher(String code,boolean lock){
        var v=s.one("select v.*,p.full_name,p.email,p.phone,p.event_id,e.demo from campaign_private.voucher v join campaign_private.participant p on p.id=v.participant_id join campaign_private.event e on e.id=p.event_id where v.code=?"+(lock?" for update of v":""),code.strip().toUpperCase(Locale.ROOT));
        var out=new LinkedHashMap<>(v);
        out.put("reward",s.object(out.remove("reward_snapshot")));
        out.put("valid",v.get("redeemed_at")==null&&Instant.now().isBefore(CampaignService.instant(v.get("expires_at"))));
        return out;
    }
    public Map<String,Object> redeem(String code,String business,String transaction,String actor){
        require(!transaction.isBlank()&&transaction.length()<=180,"Record the customer transaction or collection reference.");
        var v=voucher(code,true);
        require(Boolean.TRUE.equals(v.get("valid")),"This voucher is redeemed or expired.");
        var reward=s.object(s.encode(v.get("reward")));
        require(Objects.equals(reward.get("business"),business),"This reward belongs to a different subsidiary.");
        int changed=s.jdbc().update("update campaign_private.voucher set redeemed_at=now(),redeemed_by=?,redemption_ref=? where id=? and redeemed_at is null and expires_at>now()",actor,transaction,v.get("id"));
        if(changed!=1)throw conflict("This voucher has already been redeemed or expired.");
        s.audit((UUID)v.get("event_id"),actor,"REDEEM",Map.of("voucher",v.get("id"),"business",business,"transaction",transaction));
        return voucher(code,false);
    }
    public void conversion(UUID id,boolean converted,String reference,String actor){
        var p=s.one("select * from campaign_private.participant where id=?",id);
        require(!converted||!reference.isBlank(),"A customer transaction reference is required.");
        s.jdbc().update("update campaign_private.participant set converted=?,transaction_ref=? where id=?",converted,text(reference,180),id);
        s.audit((UUID)p.get("event_id"),actor,"LEAD_CONVERSION",Map.of("participant",id,"converted",converted,"reference",reference));
    }
    public void maintain(){
        var attempts=s.jdbc().queryForList("select participant_id from campaign_private.attempt where status='PLAYING' and deadline<now() limit 500");
        for(var row:attempts){
            var p=s.one("select * from campaign_private.participant where id=?",row.get("participant_id"));
            var e=s.event((UUID)p.get("event_id"),true);
            var a=s.attempt((UUID)p.get("id"));
            if("PLAYING".equals(a.get("status")))s.completePerformance(e,a,s.object(a.get("state")));
        }
        for(var v:s.jdbc().queryForList("select v.id,v.code,v.expires_at,p.email,p.event_id from campaign_private.voucher v join campaign_private.participant p on p.id=v.participant_id where v.redeemed_at is null and v.expires_at>now() and v.expires_at<now()+interval '7 days'")){
            s.message(s.event((UUID)v.get("event_id"),false),String.valueOf(v.get("email")),"EXPIRY","Your ADESA reward expires soon","Your reward "+v.get("code")+" expires on "+v.get("expires_at"),"expiry:"+v.get("id"));
        }
        s.jdbc().update("delete from campaign_private.rate where bucket<?",Instant.now().getEpochSecond()/60-5);
        s.jdbc().update("delete from campaign_private.recovery where expires_at<now()-interval '1 day'");
    }
}
