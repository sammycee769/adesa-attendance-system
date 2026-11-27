package com.adesa.attendance.vendor;

import java.util.Base64;
import javax.sql.DataSource;
import java.sql.Connection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VendorDatabaseConfigurationTest {
    @Test void vendorTransactionsDoNotReplaceCoreJdbcOrTransactionManager() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", java.util.Map.of(
                    "vendor.enabled", "true", "vendor.encryption-key", Base64.getEncoder().encodeToString(new byte[32]))));
            context.register(Core.class, VendorDatabaseConfiguration.class, VendorCrypto.class, VendorService.class);
            context.refresh();
            var service = context.getBean(VendorService.class);
            assertThat(AopUtils.isAopProxy(service)).isTrue();
            service.available();
            assertThat(service.isDemo()).isFalse();
            verify(context.getBean(Connection.class), never()).commit();
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
            assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
            assertThat(service.jdbc()).isSameAs(context.getBean(VendorTransactions.class).jdbc());
            assertThat(service.jdbc()).isNotSameAs(context.getBean(JdbcTemplate.class));
            verifyNoInteractions(context.getBean(PlatformTransactionManager.class));
            verify(context.getBean(Connection.class), atLeastOnce()).commit();
        }
    }
    @Test void disabledModuleDoesNotOpenASecondaryConnectionOrCloseCoreDataSource() {
        var primary = mock(DataSource.class);
        try (var transactions = new VendorTransactions(primary, false, "invalid", "", "")) {
            assertThat(transactions.jdbc().getDataSource()).isSameAs(primary);
        }
        verifyNoInteractions(primary);
    }
    @Test void remoteConnectionsFailClosedWithoutTls() {
        assertThatThrownBy(() -> new VendorTransactions(mock(DataSource.class), true,
                "jdbc:postgresql://example.invalid/database", "synthetic", "synthetic"))
                .hasMessageContaining("encrypted TLS");
    }
    @Configuration @EnableTransactionManagement static class Core {
        @Bean Connection connection() throws Exception { var c = mock(Connection.class); when(c.getAutoCommit()).thenReturn(true); return c; }
        @Bean DataSource dataSource(Connection connection) throws Exception { var ds = mock(DataSource.class); when(ds.getConnection()).thenReturn(connection); return ds; }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
