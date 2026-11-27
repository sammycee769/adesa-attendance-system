package com.adesa.attendance.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CampaignProxyTest {
    @Test void collaboratorsWorkThroughSpringClassProxyWithoutReadingProxyFields() {
        var db=new JdbcTemplate(){
            @Override public List<Map<String,Object>> queryForList(String sql){
                return List.of(Map.of("id",UUID.randomUUID(),"config","{}"));
            }
        };
        var target=new CampaignService(db,new ObjectMapper());
        var factory=new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        var proxy=(CampaignService)factory.getProxy();
        assertThat(proxy.jdbc()).isSameAs(db);
        assertThat(new CampaignAdmin(proxy).events()).hasSize(1);
        assertThat(proxy.encode(Map.of("demo",true))).isEqualTo("{\"demo\":true}");
    }
}
