package com.adesa.attendance.vendor;
import org.springframework.context.annotation.*;import org.springframework.boot.*;import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;import org.springframework.core.env.Environment;import org.springframework.security.crypto.password.PasswordEncoder;import com.adesa.attendance.employee.*;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.authority.SimpleGrantedAuthority;import org.springframework.security.core.context.SecurityContextHolder;import java.util.*;
@Configuration @Profile("vendor-local")
public class VendorLocalDemo {
 public static final String URL="jdbc:postgresql://127.0.0.1:55442/adesa_vendor_demo";
 @Bean FlywayMigrationStrategy vendorMigrations(Environment e){if(Arrays.asList(e.getActiveProfiles()).contains("prod")||!URL.equals(e.getProperty("spring.datasource.url"))||!e.getProperty("vendor.demo",Boolean.class,false)||e.getProperty("vendor.mail.enabled",Boolean.class,false)||e.getProperty("vendor.mail.live-approved",Boolean.class,false)||!"smtp".equals(e.getProperty("adesa.mail.provider"))||!e.getProperty("adesa.mail.host","").isBlank())throw new IllegalStateException("vendor-local requires its dedicated loopback database and disabled live mail before migrations");return flyway->flyway.migrate();}
 @Bean ApplicationRunner seedVendors(VendorDemoFixtures fixtures){return args->fixtures.seed();}
}
