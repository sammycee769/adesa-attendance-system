package com.adesa.attendance.campaign;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class CampaignPrivacyTest {
    private final CampaignService service = new CampaignService(new JdbcTemplate() {
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) { return List.of(); }
    }, new ObjectMapper());
    private final UUID participantId=UUID.randomUUID();
    private Map<String,Object> participant() { return Map.of("id",participantId,"full_name","Synthetic Participant","interest","ALL","marketing_consent",false,"email","private@example.test","phone","+2348000000001","session_hash","not-public"); }
    private Map<String,Object> attempt(String game,Map<String,Object> state) { var a=new HashMap<String,Object>();a.put("id",UUID.randomUUID());a.put("game",game);a.put("status","PLAYING");a.put("sequence",0);a.put("state",service.encode(state));return a; }
    private Map<String,Object> event() { return Map.of("slug","demo","demo",true); }
    @Test void memoryDoesNotExposeTheDeckOrPrivateRegistrationDetails() {
        var state=Map.<String,Object>of("deck",List.of(0,1,2,3,4,5,0,1,2,3,4,5),"matched",List.of(),"open",List.of(),"moves",0,"lockUntil",0L);
        String response=service.encode(service.publicState(participant(),event(),attempt("MATCH",state)));
        assertThat(response).doesNotContain("\"deck\"","private@example.test","session_hash","+2348000000001").contains("\"faces\":[null,null,null,null,null,null,null,null,null,null,null,null]");
    }
    @Test void expiredMismatchFacesCloseWithoutExposingOtherTiles() {
        var state=Map.<String,Object>of("deck",List.of(0,1,2,3,4,5,0,1,2,3,4,5),"matched",List.of(2,8),"open",List.of(0,1),"moves",2,"lockUntil",System.currentTimeMillis()-1000);
        var output=service.publicState(participant(),event(),attempt("MATCH",state));
        var faces=(List<?>)((Map<?,?>)output.get("attempt")).get("faces");
        assertThat(faces.get(0)).isNull();assertThat(faces.get(1)).isNull();assertThat(faces.get(2)).isEqualTo(2);assertThat(faces.get(8)).isEqualTo(2);
    }
    @Test void triviaExposesOnlyTheCurrentQuestionWithoutAnswerKeys() {
        var state=Map.<String,Object>of("questions",List.of(Map.of("text","What does CNG stand for?","options",List.of("A","B","C","D"),"correct",0,"source","Management approved")),"answers",List.of(),"score",0);
        String response=service.encode(service.publicState(participant(),event(),attempt("TRIVIA",state)));
        assertThat(response).contains("What does CNG stand for?","\"score\":null").doesNotContain("\"correct\"","\"questions\"","Management approved");
    }
    @Test void monetaryPrizeLabelsAlwaysContainTheirConfiguredFixedValue() {
        assertThat(service.publicPrize(Map.of("name","Fixed-value advertising voucher","value_ngn",25000)).get("name")).isEqualTo("Fixed-value advertising voucher — NGN 25,000");
        assertThat(service.publicPrize(Map.of("name","NGN 10,000 eligible service voucher","value_ngn",20000)).get("name")).isEqualTo("NGN 20,000 eligible service voucher");
        assertThat(service.publicPrize(Map.of("name","Complimentary assessment")).get("name")).isEqualTo("Complimentary assessment");
    }
}
