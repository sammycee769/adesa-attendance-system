package com.adesa.attendance.vendor;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Vendor-only connection/transactions; deliberately not registered as primary JDBC/TM beans. */
public final class VendorTransactions implements AutoCloseable {
    private final DataSource source;
    private final boolean owned;
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private final TransactionTemplate template;

    public VendorTransactions(DataSource primary, boolean enabled, String url, String user, String password) {
        if (!enabled || url.isBlank()) {
            source = primary;
            owned = false;
        } else {
            if (!url.startsWith("jdbc:postgresql://") || url.contains("@") || user.isBlank() || password.isBlank()) {
                throw new IllegalStateException("Configure a separate restricted vendor PostgreSQL connection");
            }
            var address = java.net.URI.create(url.substring("jdbc:".length()));
            if (!java.util.Set.of("localhost", "127.0.0.1", "::1").contains(address.getHost())) {
                var parameters = new java.util.HashMap<String, String>();
                for (String item : java.util.Objects.toString(address.getRawQuery(), "").split("&")) {
                    String[] pair = item.split("=", 2);
                    if (pair.length == 2) {
                        String name = java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8);
                        if (parameters.put(name, java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8)) != null)
                            throw new IllegalStateException("Duplicate vendor connection parameters are not permitted");
                    }
                }
                if (!java.util.Set.of("require", "verify-ca", "verify-full").contains(parameters.getOrDefault("sslmode", "")))
                    throw new IllegalStateException("Remote vendor database connections must require encrypted TLS");
            }
            var config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setPoolName("VendorPrivate");
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(15000);
            source = new HikariDataSource(config);
            owned = true;
        }
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        manager = new DataSourceTransactionManager(source);
        template = new TransactionTemplate(manager);
    }
    public JdbcTemplate jdbc() { return jdbc; }
    public DataSourceTransactionManager manager() { return manager; }
    public TransactionTemplate template() { return template; }
    @Override public void close() { if (owned) ((HikariDataSource) source).close(); }
}
