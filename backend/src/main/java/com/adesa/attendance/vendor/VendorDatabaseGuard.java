package com.adesa.attendance.vendor;
import org.springframework.boot.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.core.annotation.Order;import org.springframework.core.Ordered;import org.springframework.stereotype.Component;import org.springframework.jdbc.core.JdbcTemplate;
@Component @Order(Ordered.HIGHEST_PRECEDENCE) @ConditionalOnProperty(name="vendor.enabled",havingValue="true")
public final class VendorDatabaseGuard implements ApplicationRunner {
 private final JdbcTemplate db;private final VendorCrypto crypto;
 public VendorDatabaseGuard(JdbcTemplate db,VendorCrypto crypto){this.db=db;this.crypto=crypto;}
 public void run(ApplicationArguments args){boolean bypass=Boolean.TRUE.equals(db.queryForObject("select rolsuper or rolbypassrls from pg_roles where rolname=current_user",Boolean.class));if(bypass||!crypto.configured())throw new IllegalStateException("Vendor Management requires an encryption key and a non-superuser database role without BYPASSRLS");Boolean forced=db.queryForObject("select count(*)=8 and bool_and(relrowsecurity and relforcerowsecurity) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='vendor_private' and c.relname in ('application','revision','document','audit','note','notice','outbox','download')",Boolean.class);if(!Boolean.TRUE.equals(forced))throw new IllegalStateException("Vendor private row-security policies are not installed");}
}
