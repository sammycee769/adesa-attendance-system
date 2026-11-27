package com.adesa.attendance.campaign;
import java.util.*;
/** Management catalogue. Production drafts always start unapproved with zero inventory. */ final class CampaignCatalogue {
    record Item(String business,String name,String category,Long value,String terms){
    }
    static final List<Item> ITEMS=List.of( new Item("ENERGY","Complimentary vehicle conversion eligibility assessment","CONSULTATION",null,"Assessment only; conversion eligibility requires review."), new Item("ENERGY","NGN 10,000 first conversion voucher","PREMIUM",10000L,"Applies to a first eligible paid ADESA conversion."), new Item("ENERGY","Complimentary post-conversion maintenance check","PREMIUM",null,"After an eligible conversion; parts and repairs excluded."), new Item("ENERGY","NGN 25,000 first conversion voucher","GRAND",25000L,"Applies to a first eligible paid ADESA conversion."), new Item("MEDIA","Complimentary advertising consultation","CONSULTATION",null,"Consultation appointment required."), new Item("MEDIA","Complimentary billboard location recommendation","REGULAR",null,"Recommendation only; advertising placement separately quoted."), new Item("MEDIA","Complimentary artwork design with a paid advertising booking","PREMIUM",null,"Requires a paid eligible advertising booking and agreed design scope."), new Item("MEDIA","Fixed-value advertising voucher — pending approval","GRAND",null,"Management must approve the fixed NGN amount and redemption conditions."), new Item("SERVICES","Complimentary initial consultation","CONSULTATION",null,"Initial consultation only; subsequent work separately agreed."), new Item("SERVICES","NGN 10,000 eligible service voucher","PREMIUM",10000L,"Eligible paid services must be agreed with ADESA Services."), new Item("SERVICES","Management-approved fixed-value service voucher — pending approval","GRAND",null,"Management must approve the fixed NGN amount and eligible services."), new Item("GENERAL","Any available gifts","GIFT",null,"Collect from authorised ADESA staff, subject to available inventory.") );
    static void seed(CampaignService s,UUID event,boolean demo){
        for(Item item:ITEMS){
            boolean approved=demo&&!item.name().contains("pending approval");
            s.jdbc().update("insert into campaign_private.prize(id,event_id,business,name,category,value_ngn,quantity,approved,active,terms) values(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),event,item.business(),item.name(),item.category(),item.value(),demo?20:0,approved,approved,(demo?"DEMO ONLY. ":"")+item.terms());
        }
    }
}
