package com.adesa.attendance.campaign;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CampaignRulesTest {
    @Test void normalisationDetectsEquivalentRegistrationIdentities(){
        assertThat(CampaignRules.email(" PERSON@Example.Test ")).isEqualTo("person@example.test");
        for(String value:List.of("08012345678","2348012345678","+234 801 234 5678","002348012345678"))assertThat(CampaignRules.phone(value)).isEqualTo("+2348012345678");
        assertThatThrownBy(()->CampaignRules.phone("1234")).hasMessageContaining("international");
    }
    @Test void performanceThresholdsAreExact(){
        for(int moves=6;moves<=8;moves++)assertThat(CampaignRules.performance("MATCH",6,moves)).isEqualTo("GRAND");
        for(int moves=9;moves<=12;moves++)assertThat(CampaignRules.performance("MATCH",6,moves)).isEqualTo("PREMIUM");
        assertThat(CampaignRules.performance("MATCH",6,13)).isEqualTo("REGULAR");
        for(int score=0;score<=5;score++)assertThat(CampaignRules.performance("MATCH",score,12)).isEqualTo(score>=4?"CONSULTATION":"GIFT");
        String[] trivia={
            "GIFT","GIFT","CONSULTATION","REGULAR","PREMIUM","GRAND"
        }
        ;
        for(int i=0;i<6;i++)assertThat(CampaignRules.performance("TRIVIA",i,0)).isEqualTo(trivia[i]);
    }
    @Test void weightsHaveExactBoundariesAndExhaustedCategoriesAreExcluded(){
        var w=Map.of("REGULAR",40,"CONSULTATION",25,"PREMIUM",20,"GIFT",10,"GRAND",5);
        var counts=new HashMap<String,Integer>();
        for(int i=0;i<100;i++)counts.merge(CampaignRules.category(w,w.keySet(),i),1,Integer::sum);
        assertThat(counts).isEqualTo(w);
        for(int i=0;i<30;i++)assertThat(CampaignRules.category(w,Set.of("CONSULTATION","GRAND"),i)).isEqualTo(i<25?"CONSULTATION":"GRAND");
        assertThatThrownBy(()->CampaignRules.category(w,Set.of(),0)).hasMessageContaining("No approved");
    }
    @Test void discountsAndNonParticipatingBusinessesAreRejected(){
        for(String s:List.of("Save 20%","percentage discount","ten percent off","Consultation\nwith ten percent off\nnext booking"))assertThatThrownBy(()->CampaignRules.noDiscount(s)).hasMessageContaining("not permitted");
        assertThat(CampaignRules.BUSINESSES).containsExactlyInAnyOrder("ENERGY","MEDIA","SERVICES","GENERAL");
        CampaignRules.noDiscount("NGN 10,000 eligible service voucher");
    }
    @Test void tokensAreUniqueAndOnlyHashesPersist(){
        var tokens=new HashSet<String>();
        for(int i=0;i<10000;i++){
            String token=CampaignRules.token();
            assertThat(token).hasSize(43);
            assertThat(CampaignRules.hash(token)).hasSize(64).isNotEqualTo(token);
            assertThat(tokens.add(token)).isTrue();
        }
    }
    @Test void csvCannotExecuteSpreadsheetFormulas(){
        for(String s:List.of("=SUM(1,2)","+2348012345678","@evil"," -1"," =HYPERLINK(x)"))assertThat(CampaignStaffController.cell(s)).startsWith("\"'");
        assertThat(CampaignStaffController.cell("Jane, Doe")).isEqualTo("\"Jane, Doe\"");
        assertThat(CampaignStaffController.cell("quoted\"text")).isEqualTo("\"quoted\"\"text\"");
    }
}
