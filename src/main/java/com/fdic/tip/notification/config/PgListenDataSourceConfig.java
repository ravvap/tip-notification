package com.fdic.tip.notification.config;

import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * A dedicated, NON-pooled DataSource used exclusively by PgNotifyListenerService.
 *
 * This must not be the same DataSource backing HikariCP/JPA. A LISTEN session
 * lives on one physical connection for as long as the app wants notifications;
 * if that connection were drawn from the normal connection pool, Hikari would
 * eventually reclaim and reuse it for an unrelated query, silently ending the
 * LISTEN with no error surfaced anywhere.
 */
@Configuration
public class PgListenDataSourceConfig {

    @Bean
    public DataSource notifyListenerDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
