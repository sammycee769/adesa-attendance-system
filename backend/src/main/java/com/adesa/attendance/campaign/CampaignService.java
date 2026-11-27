package com.adesa.attendance.campaign;
import static com.adesa.attendance.campaign.CampaignRules.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Service @Transactional public class CampaignService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public CampaignService(JdbcTemplate db,ObjectMapper json){
        this.db=db;
        this.json=json;
    }
    // Invoke a method on the Spring proxy; never access collaborators through its fields.
    public JdbcTemplate jdbc(){
        return db;
    }
    String encode(Object value){
        try{
            return json.writeValueAsString(value);
        }
        catch(Exception e){
            throw new IllegalStateException(e);
        }
    }
    Map<String,Object> object(Object value){
        try{
            return json.readValue(String.valueOf(value),new TypeReference<LinkedHashMap<String,Object>>(){
            }
            );
        }
        catch(Exception e){
            throw new IllegalStateException(e);
        }
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object v){
        return (List<Map<String,Object>>)v;
    }
    static int number(Object value){
        require(value instanceof Number,"A numeric setting is required.");
        double n=((Number)value).doubleValue();
        require(Double.isFinite(n)&&n==Math.rint(n)&&n>=Integer.MIN_VALUE&&n<=Integer.MAX_VALUE,"Use a whole number within the allowed range.");
        return (int)n;
    }
    static Instant instant(Object value){
        return value instanceof java.sql.Timestamp t?t.toInstant():value instanceof java.time.OffsetDateTime d?d.toInstant():Instant.parse(value.toString());
    }
    Map<String,Object> one(String sql,Object...args){
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found.");
        return rows.getFirst();
    }
    Map<String,Object> event(String slug,boolean lock){
        return one("select * from campaign_private.event where slug=?"+(lock?" for update":""),slug);
    }
    Map<String,Object> event(UUID id,boolean lock){
        return one("select * from campaign_private.event where id=?"+(lock?" for update":""),id);
    }
    Map<String,Object> config(Map<String,Object> e){
        return object(e.get("config"));
    }
    void audit(UUID event,String actor,String action,Object detail){
        db.update("insert into campaign_private.audit(event_id,actor,action,detail) values (?,?,?,?::jsonb)",event,actor,action,encode(detail));
    }
    public void rate(String key,int limit){
        long bucket=Instant.now().getEpochSecond()/60;
        Integer hits=db.queryForObject("insert into campaign_private.rate(key_hash,bucket) values(?,?) on conflict(key_hash,bucket) do update set hits=campaign_private.rate.hits+1 returning hits",Integer.class,hash(key),bucket);
        if(hits!=null&&hits>limit)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Please wait a minute and try again.");
    }
    void open(Map<String,Object> e){
        Instant now=Instant.now();
        require(Boolean.TRUE.equals(e.get("active"))&&Boolean.TRUE.equals(e.get("approved"))&&!now.isBefore(instant(e.get("starts_at")))&&now.isBefore(instant(e.get("ends_at"))),"This event is not open for participation.");
    }
    List<Map<String,Object>> available(UUID event,String interest){
        return db.queryForList("select * from campaign_private.prize where event_id=? and active and approved and issued<quantity and (business=? or ?='ALL' or business='GENERAL') order by category,business,name",event,interest,interest);
    }
    Map<String,Object> publicPrize(Map<String,Object> p){
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","business","name","category","value_ngn","terms"))out.put(key,p.get(key));
        if(p.get("value_ngn") instanceof Number value){
            String amount="NGN "+String.format(Locale.ROOT,"%,d",value.longValue());
            String name=String.valueOf(p.get("name"));
            out.put("name",name.matches("(?is).*\\bNGN\\s*[0-9][0-9,]*.*")?name.replaceAll("(?i)\\bNGN\\s*[0-9][0-9,]*",amount):name+" — "+amount);
        }
        return out;
    }
    public Map<String,Object> publicEvent(String slug){
        var e=event(slug,false);
        var c=config(e);
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("slug","name","description","starts_at","ends_at","active","approved","demo"))out.put(key,e.get(key));
        out.put("games",c.get("games"));
        out.put("termsVersion",c.get("termsVersion"));
        out.put("validityDays",c.get("validityDays"));
        out.put("terms",c.get("terms"));
        out.put("prizes",available((UUID)e.get("id"),"ALL").stream().map(this::publicPrize).toList());
        out.put("inventoryPolicy","Exhausted categories are excluded; the remaining configured category weights are renormalised. Rewards are subject to approved availability.");
        return out;
    }
    void message(Map<String,Object> e,String recipient,String kind,String subject,String body,String dedupe){
        db.update("insert into campaign_private.message(id,event_id,recipient,kind,subject,body,dedupe_key) values (?,?,?,?,?,?,?) on conflict(dedupe_key) do nothing",UUID.randomUUID(),e.get("id"),recipient,kind,subject,body,dedupe);
    }
    void notifyStaff(Map<String,Object> e,String kind,String subject,String body,String key){
        String recipient=text(config(e).get("notifyEmail"),254);
        if(!recipient.isBlank())message(e,email(recipient),kind,subject,body,key);
    }
    public Map<String,Object> register(String slug,Map<String,Object> input){
        var e=event(slug,true);
        open(e);
        var c=config(e);
        String name=text(input.get("fullName"),120),mail=email(text(input.get("email"),254)),phone=phone(text(input.get("phone"),40));
        require(!name.isBlank(),"Enter your full name.");
        require(Boolean.TRUE.equals(input.get("termsAccepted")),"Accept the participation terms and privacy notice.");
        require(Objects.equals(c.get("termsVersion"),input.get("termsVersion")),"The terms have changed. Refresh the campaign page.");
        String interest=text(input.get("interest"),12);
        require(Set.of("ENERGY","MEDIA","SERVICES","ALL").contains(interest),"Select a participating business.");
        if(Boolean.TRUE.equals(e.get("demo")))require(mail.endsWith(".test"),"Demo registrations must use a .test email address.");
        if(!db.queryForList("select id from campaign_private.participant where event_id=? and (email=? or phone=?)",e.get("id"),mail,phone).isEmpty())throw conflict("These details are already registered. Use secure recovery to continue your existing attempt.");
        UUID id=UUID.randomUUID();
        String token=token();
        db.update("insert into campaign_private.participant(id,event_id,full_name,email,phone,company,industry,interest,terms_version,marketing_consent,session_hash,session_expires) values(?,?,?,?,?,?,?,?,?,?,?,?)",id,e.get("id"),name,mail,phone,text(input.get("company"),160),text(input.get("industry"),120),interest,c.get("termsVersion"),Boolean.TRUE.equals(input.get("marketingConsent")),hash(token),java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(2))));
        message(e,mail,"REGISTRATION","Welcome to ADESA Play, Connect & Win",name+", your registration is confirmed. You have one official prize-winning attempt across all games. Event: "+e.get("name"),"registration:"+id);
        notifyStaff(e,"NEW_LEAD","New event registration",name+" registered interest in "+interest,"new-lead:"+id);
        audit((UUID)e.get("id"),"participant:"+id,"REGISTER",Map.of("interest",interest,"marketingConsent",Boolean.TRUE.equals(input.get("marketingConsent"))));
        return Map.of("session",token,"participant",viewParticipant(one("select * from campaign_private.participant where id=?",id)));
    }
    Map<String,Object> participant(String token){
        if(token==null||token.isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please register or recover your participant session.");
        var found=db.queryForList("select * from campaign_private.participant where session_hash=? and session_expires>now()",hash(token));
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Participant session expired. Use secure recovery.");
        return found.getFirst();
    }
    Map<String,Object> viewParticipant(Map<String,Object> p){
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","full_name","interest","marketing_consent"))out.put(key,p.get(key));
        return out;
    }
    public void requestRecovery(String slug,Map<String,Object> input){
        var e=event(slug,true);
        String mail=email(text(input.get("email"),254)),phone=phone(text(input.get("phone"),40));
        var rows=db.queryForList("select id,full_name,email from campaign_private.participant where event_id=? and email=? and phone=?",e.get("id"),mail,phone);
        if(rows.isEmpty())return;
        var p=rows.getFirst();
        String token=token();
        db.update("insert into campaign_private.recovery values(?,?,?,null)",hash(token),p.get("id"),java.sql.Timestamp.from(Instant.now().plusSeconds(900)));
        String url=text(config(e).get("publicUrl"),500)+"/play/"+e.get("slug")+"?recover="+token;
        message(e,mail,"RECOVERY","Resume your ADESA event participation","Use this single-use link within 15 minutes: "+url+"\nIt restores your existing attempt, never a second prize.","recovery:"+hash(token));
    }
    public Map<String,Object> recover(String token){
        var r=one("select * from campaign_private.recovery where token_hash=? for update",hash(token));
        require(r.get("consumed_at")==null&&Instant.now().isBefore(instant(r.get("expires_at"))),"Recovery link is invalid or expired.");
        String session=token();
        db.update("update campaign_private.recovery set consumed_at=now() where token_hash=?",hash(token));
        db.update("update campaign_private.participant set session_hash=?,session_expires=? where id=?",hash(session),java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(2))),r.get("participant_id"));
        return Map.of("session",session);
    }
    Map<String,Object> attempt(UUID participant){
        var rows=db.queryForList("select * from campaign_private.attempt where participant_id=? for update",participant);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Map<String,Object> state(String session){
        var p=participant(session);
        var e=event((UUID)p.get("event_id"),true);
        var a=attempt((UUID)p.get("id"));
        if(a!=null&&"PLAYING".equals(a.get("status"))&&Instant.now().isAfter(instant(a.get("deadline"))))completePerformance(e,a,object(a.get("state")));
        return publicState(p,e,a==null?null:attempt((UUID)p.get("id")));
    }
    public Map<String,Object> begin(String session,Map<String,Object> input){
        var p=participant(session);
        var e=event((UUID)p.get("event_id"),true);
        open(e);
        var a=attempt((UUID)p.get("id"));
        if(a!=null)return publicState(p,e,a);
        String game=text(input.get("game"),12);
        require(GAMES.contains(game)&&((List<?>)config(e).get("games")).contains(game),"This game is not available.");
        UUID id=UUID.randomUUID();
        var s=new LinkedHashMap<String,Object>();
        String status="PLAYING";
        if(game.equals("MATCH")){
            var deck=new ArrayList<Integer>();
            for(int i=0;i<6;i++){
                deck.add(i);
                deck.add(i);
            }
            Collections.shuffle(deck,RANDOM);
            s.put("deck",deck);
            s.put("matched",new ArrayList<Integer>());
            s.put("open",new ArrayList<Integer>());
            s.put("moves",0);
            s.put("lockUntil",0L);
        }
        if(game.equals("TRIVIA")){
            var questions=new ArrayList<>(list(config(e).get("questions")));
            questions.removeIf(q->!Boolean.TRUE.equals(q.get("approved")));
            Collections.shuffle(questions,RANDOM);
            require(questions.size()>=5,"Five approved trivia questions are required.");
            s.put("questions",questions.subList(0,5));
            s.put("answers",new ArrayList<Integer>());
            s.put("score",0);
        }
        if(game.equals("SPIN")||game.equals("SCRATCH")){
            var prizes=available((UUID)e.get("id"),String.valueOf(p.get("interest")));
            require(!prizes.isEmpty(),"No approved rewards are currently available. Please speak to the ADESA team.");
            var wheel=new ArrayList<Map<String,Object>>();
            for(String category:CATEGORIES){
                var group=prizes.stream().filter(prize->category.equals(prize.get("category"))).toList();
                if(!group.isEmpty())wheel.add(publicPrize(group.get(RANDOM.nextInt(group.size()))));
            }
            s.put("wheel",wheel);
            status="AWARDED";
        }
        Instant now=Instant.now();
        db.update("insert into campaign_private.attempt(id,participant_id,game,status,started_at,deadline,state) values(?,?,?,?,?,?,?::jsonb)",id,p.get("id"),game,status,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now.plusSeconds(60)),encode(s));
        a=attempt((UUID)p.get("id"));
        if(status.equals("AWARDED")){
            var prizes=available((UUID)e.get("id"),String.valueOf(p.get("interest")));
            Map<String,Integer> weights=weights(config(e));
            Set<String> cats=prizes.stream().map(x->String.valueOf(x.get("category"))).collect(Collectors.toSet());
            int total=cats.stream().mapToInt(x->weights.getOrDefault(x,0)).sum();
            require(total>0,"No weighted prizes are available.");
            String cat=category(weights,cats,RANDOM.nextInt(total));
            var selected=list(s.get("wheel")).stream().filter(x->cat.equals(x.get("category"))).findFirst().orElseThrow();
            var chosen=prizes.stream().filter(x->x.get("id").toString().equals(selected.get("id").toString())).findFirst().orElseThrow();
            allocate(e,p,a,chosen,cat);
        }
        audit((UUID)e.get("id"),"participant:"+p.get("id"),"START_GAME",Map.of("game",game));
        return publicState(p,e,attempt((UUID)p.get("id")));
    }
    Map<String,Integer> weights(Map<String,Object> c){
        var input=object(encode(c.get("weights")));
        var w=new LinkedHashMap<String,Integer>();
        for(String key:CATEGORIES)w.put(key,number(input.get(key)));
        return w;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> action(String session,Map<String,Object> input){
        var p=participant(session);
        var e=event((UUID)p.get("event_id"),true);
        var a=attempt((UUID)p.get("id"));
        require(a!=null,"Choose a game first.");
        if(!"PLAYING".equals(a.get("status")))return publicState(p,e,a);
        if(Instant.now().isAfter(instant(a.get("deadline")))){
            completePerformance(e,a,object(a.get("state")));
            return publicState(p,e,attempt((UUID)p.get("id")));
        }
        require(input.get("sequence") instanceof Number&&number(input.get("sequence"))==number(a.get("sequence")),"This action is stale. Resume the current game state.");
        var s=object(a.get("state"));
        String game=String.valueOf(a.get("game"));
        long now=Instant.now().toEpochMilli();
        if(game.equals("MATCH")){
            require("flip".equals(input.get("type")),"Invalid memory action.");
            int tile=number(input.get("index"));
            require(tile>=0&&tile<12,"Invalid tile.");
            var matched=(List<Integer>)s.get("matched");
            var opened=(List<Integer>)s.get("open");
            long until=((Number)s.get("lockUntil")).longValue();
            require(now>=until,"Wait for the tiles to close.");
            if(opened.size()==2)opened.clear();
            require(!matched.contains(tile)&&!opened.contains(tile),"That tile is already open.");
            long last=((Number)s.getOrDefault("lastFlip",0L)).longValue();
            require(now-last>=120,"Please play one tile at a time.");
            opened.add(tile);
            s.put("lastFlip",now);
            if(opened.size()==2){
                s.put("moves",number(s.get("moves"))+1);
                var deck=(List<Integer>)s.get("deck");
                if(deck.get(opened.get(0)).equals(deck.get(opened.get(1)))){
                    matched.addAll(opened);
                    opened.clear();
                }
                else s.put("lockUntil",now+1000);
            }
            if(matched.size()==12)completePerformance(e,a,s);
        }
        else if(game.equals("TRIVIA")){
            require("answer".equals(input.get("type")),"Invalid trivia action.");
            var answers=(List<Integer>)s.get("answers");
            int option=number(input.get("option"));
            require(option>=0&&option<4&&answers.size()<5,"Invalid answer.");
            var questions=list(s.get("questions"));
            if(number(questions.get(answers.size()).get("correct"))==option)s.put("score",number(s.get("score"))+1);
            answers.add(option);
            if(answers.size()==5)completePerformance(e,a,s);
        }
        else throw conflict("This game has no score actions.");
        db.update("update campaign_private.attempt set state=?::jsonb,sequence=sequence+1 where id=?",encode(s),a.get("id"));
        return publicState(p,e,attempt((UUID)p.get("id")));
    }
    @SuppressWarnings("unchecked") void completePerformance(Map<String,Object> e,Map<String,Object> a,Map<String,Object> s){
        String game=String.valueOf(a.get("game"));
        int score=game.equals("MATCH")?((List<?>)s.get("matched")).size()/2:number(s.get("score"));
        int moves=game.equals("MATCH")?number(s.get("moves")):0;
        String tier=performance(game,score,moves);
        db.update("update campaign_private.attempt set status='CHOOSING',tier=?,state=?::jsonb,completed_at=now() where id=?",tier,encode(s),a.get("id"));
    }
    List<Map<String,Object>> choices(Map<String,Object> p,Map<String,Object> a){
        var options=available((UUID)p.get("event_id"),String.valueOf(p.get("interest")));
        String requested=String.valueOf(a.get("tier"));
        for(String cat:List.of(requested,"REGULAR","CONSULTATION","GIFT")){
            var found=options.stream().filter(x->cat.equals(x.get("category"))).toList();
            if(!found.isEmpty())return found;
        }
        return List.of();
    }
    public Map<String,Object> choose(String session,Map<String,Object> input){
        var p=participant(session);
        var e=event((UUID)p.get("event_id"),true);
        var a=attempt((UUID)p.get("id"));
        require(a!=null,"Choose a game first.");
        if("AWARDED".equals(a.get("status")))return publicState(p,e,a);
        require("CHOOSING".equals(a.get("status")),"Finish the game first.");
        String id=text(input.get("prizeId"),40);
        var prize=choices(p,a).stream().filter(x->x.get("id").toString().equals(id)).findFirst().orElseThrow(()->conflict("That reward is unavailable. Choose another approved option."));
        allocate(e,p,a,prize,String.valueOf(a.get("tier")));
        return publicState(p,e,attempt((UUID)p.get("id")));
    }
    void allocate(Map<String,Object> e,Map<String,Object> p,Map<String,Object> a,Map<String,Object> prize,String requested){
        int changed=db.update("update campaign_private.prize set issued=issued+1 where id=? and approved and active and issued<quantity",prize.get("id"));
        if(changed!=1)throw conflict("This reward was just exhausted. Please retry with available prizes.");
        Instant issued=Instant.now(),expires=issued.plus(Duration.ofDays(number(config(e).get("validityDays"))));
        String code="ADESA-"+token().substring(0,22).toUpperCase(Locale.ROOT);
        var snapshot=publicPrize(prize);
        snapshot.put("participantName",p.get("full_name"));
        snapshot.put("instructions",prize.get("business").equals("GENERAL")?"Collect your available gift from authorised ADESA event staff. Present this code and matching participant details.":"Present this single-use code and your registered details to the relevant ADESA business before expiry. Eligible paid transactions and reward-specific conditions apply.");
        snapshot.put("terms",prize.get("terms")+" Single-use and non-transferable. No cash alternative. Cannot be combined without approval.");
        db.update("insert into campaign_private.voucher(id,participant_id,prize_id,code,issued_at,expires_at,reward_snapshot,requested_category) values(?,?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),p.get("id"),prize.get("id"),code,java.sql.Timestamp.from(issued),java.sql.Timestamp.from(expires),encode(snapshot),requested);
        db.update("update campaign_private.attempt set status='AWARDED',completed_at=now() where id=?",a.get("id"));
        String body=p.get("full_name")+", your reward is "+snapshot.get("name")+".\nBusiness: "+prize.get("business")+"\nCode: "+code+"\nIssued: "+issued+"\nExpires: "+expires+"\n"+snapshot.get("instructions")+"\n"+snapshot.get("terms");
        message(e,String.valueOf(p.get("email")),"REWARD","Your ADESA reward and redemption instructions",body,"reward:"+p.get("id"));
        if("GRAND".equals(prize.get("category")))notifyStaff(e,"GRAND_WIN","Grand prize issued",p.get("full_name")+" won "+prize.get("name"),"grand:"+p.get("id"));
        if(number(prize.get("quantity"))-number(prize.get("issued"))-1<=number(config(e).get("lowStockThreshold")))notifyStaff(e,"LOW_STOCK","Campaign prize inventory is low",String.valueOf(prize.get("name")),"low-stock:"+prize.get("id")+":"+prize.get("quantity"));
        audit((UUID)e.get("id"),"participant:"+p.get("id"),"AWARD",Map.of("prize",prize.get("id"),"requestedCategory",requested,"actualCategory",prize.get("category")));
    }
    @SuppressWarnings("unchecked") Map<String,Object> publicState(Map<String,Object> p,Map<String,Object> e,Map<String,Object> a){
        var out=new LinkedHashMap<String,Object>();
        out.put("participant",viewParticipant(p));
        out.put("event",Map.of("slug",e.get("slug"),"demo",e.get("demo")));
        if(a==null){
            out.put("attempt",null);
            return out;
        }
        var view=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","game","status","started_at","deadline","sequence","tier"))view.put(key,a.get(key));
        var s=object(a.get("state"));
        String game=String.valueOf(a.get("game"));
        if(game.equals("MATCH")){
            var deck=(List<Integer>)s.get("deck");
            var matched=(List<Integer>)s.get("matched");
            var opened=(List<Integer>)s.get("open");
            long until=((Number)s.get("lockUntil")).longValue();
            var faces=new ArrayList<Object>();
            for(int i=0;i<12;i++)faces.add(matched.contains(i)||(opened.contains(i)&&(opened.size()<2||System.currentTimeMillis()<until))?deck.get(i):null);
            view.put("faces",faces);
            view.put("matched",matched);
            view.put("moves",s.get("moves"));
            view.put("pairs",matched.size()/2);
            view.put("lockUntil",until);
        }
        if(game.equals("TRIVIA")){
            var answers=(List<Integer>)s.get("answers");
            view.put("answered",answers.size());
            view.put("score","PLAYING".equals(a.get("status"))?null:s.get("score"));
            if(answers.size()<5&&"PLAYING".equals(a.get("status"))){
                var q=list(s.get("questions")).get(answers.size());
                view.put("question",Map.of("text",q.get("text"),"options",q.get("options")));
            }
        }
        if(s.containsKey("wheel"))view.put("wheel",s.get("wheel"));
        out.put("attempt",view);
        if("CHOOSING".equals(a.get("status"))){
            out.put("choices",choices(p,a).stream().map(this::publicPrize).toList());
            out.put("availabilityNotice","Reward choices reflect approved inventory. If your earned category is exhausted, the available fallback category is shown. Your original game result remains recorded.");
        }
        var rewards=db.queryForList("select code,issued_at,expires_at,redeemed_at,reward_snapshot,prize_id from campaign_private.voucher where participant_id=?",p.get("id"));
        if(!rewards.isEmpty()){
            var reward=new LinkedHashMap<>(rewards.getFirst());
            reward.put("reward",object(reward.remove("reward_snapshot")));
            out.put("voucher",reward);
        }
        out.put("serverTime",Instant.now().toString());
        return out;
    }
}
