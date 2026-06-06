package com.northwood.shared.infrastructure.db;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Wires Liquibase as a {@link SpringLiquibase} bean for every service.
 *
 * <p>Spring Boot 4.0.x does not yet ship a {@code LiquibaseAutoConfiguration},
 * so this auto-configuration provides one. Each service sets three properties
 * under {@code northwood.liquibase}:
 *
 * <ul>
 *   <li>{@code default-schema} — the service's bounded-context schema. The
 *       {@code databasechangelog} and {@code databasechangeloglock} tables
 *       live here, isolating each service's migration history.</li>
 *   <li>{@code change-log} — classpath location of the master changelog,
 *       defaults to {@code classpath:db/changelog/db.changelog-master.yaml}.</li>
 *   <li>{@code enabled} — defaults to true; set false to skip on boot.</li>
 * </ul>
 *
 * <p>The {@code northwood_erp} database is provisioned directly from
 * {@code config/postgresql/northwood_erp.sql} (mounted into Postgres's init-script
 * directory). Each service's master changelog starts empty: Liquibase
 * creates the {@code databasechangelog} bookkeeping tables on first boot
 * but applies no changesets. New schema work goes in
 * {@code <service>/src/main/resources/db/changelog/changes/}.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
public class LiquibaseConfig {

    @Bean
    public SpringLiquibase liquibase(
        DataSource dataSource,
        @Value("${northwood.liquibase.default-schema}") String defaultSchema,
        @Value("${northwood.liquibase.change-log:classpath:db/changelog/db.changelog-master.yaml}") String changeLog,
        @Value("${northwood.liquibase.enabled:true}") boolean enabled
    ) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setDefaultSchema(defaultSchema);
        liquibase.setLiquibaseSchema(defaultSchema);
        liquibase.setShouldRun(enabled);
        return liquibase;
    }
}
