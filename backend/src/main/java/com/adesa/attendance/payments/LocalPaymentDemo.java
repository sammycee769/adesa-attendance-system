package com.adesa.attendance.payments;

import com.adesa.attendance.employee.*;
import java.util.Arrays;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Dedicated local database, no production credentials or email transport. */
@Configuration
@Profile("payments-local")
class LocalPaymentDemo {
 static final String URL="jdbc:postgresql://127.0.0.1:55439/adesa_payments_local";
 @Bean FlywayMigrationStrategy paymentLocalMigrations(Environment env) {
  if(Arrays.asList(env.getActiveProfiles()).contains("prod")||!URL.equals(env.getProperty("spring.datasource.url"))
    || !"mock".equals(env.getProperty("adesa.payments.transport")) || env.getProperty("adesa.payments.live-approved",Boolean.class,false))
   throw new IllegalStateException("payments-local requires its dedicated loopback database and mock transport; prod is forbidden");
  return flyway->flyway.migrate();
 }
 @Bean ApplicationRunner syntheticPaymentAdmin(EmployeeRepository employees,PasswordEncoder encoder) {
  return args->{
   String email="admin@payments.example.test";
   if(employees.findByEmail(email)==null)employees.saveAndFlush(new Employee("LOCAL-PAYMENTS-ADMIN","Synthetic Payments Admin",email,encoder.encode("LocalPayments-Only-2026!"),EmployeeRole.ADMIN,true));
   String employeeEmail="employee@payments.example.test";
   if(employees.findByEmail(employeeEmail)==null)employees.saveAndFlush(new Employee("LOCAL-PAYMENTS-EMPLOYEE","Synthetic Employee",employeeEmail,encoder.encode("LocalPayments-Only-2026!"),EmployeeRole.EMPLOYEE,true));
  };
 }
}
