package com.adesa.attendance.campaign;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component final class CampaignMaintenance {
    private final CampaignAdmin admin;
    private final boolean enabled;
    CampaignMaintenance(CampaignAdmin admin,@Value("${campaign.enabled:false}")boolean enabled){
        this.admin=admin;
        this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${campaign.maintenance.interval-ms:60000}") public void tick(){
        if(enabled)admin.maintain();
    }
}
