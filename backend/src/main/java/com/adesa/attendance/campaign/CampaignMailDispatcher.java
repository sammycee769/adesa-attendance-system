package com.adesa.attendance.campaign;
import java.util.*;
import com.adesa.attendance.mail.MailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
@Component public class CampaignMailDispatcher {
    final CampaignService s;
    final MailService mail;
    final TransactionTemplate tx;
    final boolean enabled,approved;
    public CampaignMailDispatcher(CampaignService s,MailService mail,TransactionTemplate tx,@Value("${campaign.mail.enabled:false}")boolean enabled,@Value("${campaign.mail.live-approved:false}")boolean approved){
        this.s=s;
        this.mail=mail;
        this.tx=tx;
        this.enabled=enabled;
        this.approved=approved;
    }
    @Scheduled(fixedDelayString="${campaign.mail.interval-ms:60000}") public void dispatch(){
        if(!enabled||!approved||!mail.isDeliveryEnabled())return;
        for(int i=0;i<20;i++){
            Map<String,Object> m=tx.execute(status->{
                var rows=s.jdbc().queryForList("select m.* from campaign_private.message m join campaign_private.event e on e.id=m.event_id where m.state='PREVIEW' and not e.demo and e.approved order by m.created_at for update of m skip locked limit 1");if(rows.isEmpty())return null;var row=rows.getFirst();s.jdbc().update("update campaign_private.message set state='REVIEW',attempts=attempts+1 where id=?",row.get("id"));return row;
            }
            );
            if(m==null)return;
            try{
                String body=String.valueOf(m.get("body"));
                mail.send(String.valueOf(m.get("recipient")),String.valueOf(m.get("subject")),body,"<div style='background:#071523;color:#fff;padding:32px;font-family:Arial'><h1 style='color:#edcb75'>ADESA Play, Connect &amp; Win</h1><p style='white-space:pre-wrap'>"+escape(body)+"</p></div>");
                s.jdbc().update("update campaign_private.message set state='DELIVERED',delivered_at=now() where id=?",m.get("id"));
            }
            catch(RuntimeException ignored){
                /* Ambiguous delivery stays REVIEW, without automatic resend. */
            }
        }
    }
    static String escape(String v){
        return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
@Configuration @EnableScheduling class CampaignMailScheduling {
}
