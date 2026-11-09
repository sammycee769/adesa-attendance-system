package com.adesa.attendance.assets;

import com.adesa.attendance.employee.*;
import com.adesa.attendance.security.AuthenticatedEmployee;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import java.time.LocalDate;

/** Opt-in, loopback-only synthetic fixtures. This profile must never target a shared database. */
@Configuration
@Profile("assets-local")
class LocalAssetDemo {
 static final String URL="jdbc:postgresql://127.0.0.1:55437/adesa_assets_local";
 @Bean FlywayMigrationStrategy localOnlyMigrations(Environment env) {
  // Validate the configured destination before Flyway opens any connection.
  if(Arrays.asList(env.getActiveProfiles()).contains("prod") || !URL.equals(env.getProperty("spring.datasource.url")))
   throw new IllegalStateException("assets-local requires its dedicated loopback database and cannot run with prod");
  return flyway -> flyway.migrate();
 }
 @Bean ApplicationRunner syntheticAssets(EmployeeRepository employees,PasswordEncoder encoder,AssetService assets) {
  return args -> {
   Map<EmployeeRole,Employee> fixtures=new EnumMap<>(EmployeeRole.class);
   for(EmployeeRole role:EmployeeRole.values()) {
    String email=role.name().toLowerCase(Locale.ROOT)+"@assets.example.test";
    Employee employee=employees.findByEmail(email);
    if(employee==null) employee=employees.saveAndFlush(new Employee("LOCAL-ASSET-"+role,"Synthetic "+role,email,encoder.encode("LocalAssets-Only-2026!"),role,true));
    fixtures.put(role,employee);
   }
   Employee admin=fixtures.get(EmployeeRole.ADMIN); AuthenticatedEmployee actor=new AuthenticatedEmployee(admin.getId(),admin.getEmail(),admin.getFullName(),admin.getRole());
   // Idempotent demo initialization, separate from all real employee data.
   if(assets.demoExists()) return;
   UUID subsidiary=assets.subsidiary(new AssetRequests.SubsidiaryRequest("Synthetic Local Subsidiary"),actor);
   UUID laptop=assets.register(new AssetRequests.Registration("LAPTOP","Demo Brand","Local Laptop","SYNTHETIC-LAP-001",LocalDate.of(2025,1,1),"GOOD"),actor);
   assets.register(new AssetRequests.Registration("MOBILE_PHONE","Demo Brand","Local Phone","SYNTHETIC-PHN-001",LocalDate.of(2025,1,1),"NEW"),actor);
   Employee recipient=fixtures.get(EmployeeRole.EMPLOYEE);
   UUID assignment=assets.assign(new AssetRequests.Assign(laptop,recipient.getId(),subsidiary,LocalDate.now(),"Synthetic local demonstration"),actor);
   assets.assignmentDecision(assignment,true,new AssetRequests.Decision("Synthetic local approval"),actor);
  };
 }
}
