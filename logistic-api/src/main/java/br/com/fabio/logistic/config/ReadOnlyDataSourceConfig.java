package br.com.fabio.logistic.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Segundo DataSource, usado exclusivamente pela tool MCP executeQuery. Conecta com a role
 * read-only do Postgres (logistic_ro) e aplica statement_timeout via connection-init-sql. O
 * DataSource principal continua exclusivo do JPA e do Flyway.
 *
 * Assim que qualquer bean DataSource é declarado manualmente, a auto-configuração do Spring
 * Boot deixa de criar o DataSource principal (é @ConditionalOnMissingBean) — por isso o
 * DataSource principal também precisa ser declarado aqui explicitamente, marcado @Primary. O
 * binding passa por DataSourceProperties (não @ConfigurationProperties direto no HikariDataSource)
 * porque HikariDataSource não tem um setter "url" — só "jdbcUrl" — e é o
 * initializeDataSourceBuilder() que faz essa tradução.
 */
@Configuration
public class ReadOnlyDataSourceConfig {

    @Primary
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "dataSource")
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "logistic.readonly-datasource")
    public DataSourceProperties readOnlyDataSourceProperties() {
        return new DataSourceProperties();
    }

    // @Qualifier é obrigatório nos dois beans abaixo: existem dois candidatos de cada tipo
    // (DataSourceProperties e DataSource) e o outro é @Primary. O @Primary vence antes de o
    // Spring tentar casar pelo nome do parâmetro — sem o qualificador, a conexão read-only
    // acabava sendo a do JPA (role postgres, sem statement_timeout).
    @Bean(name = "readOnlyDataSource")
    @ConfigurationProperties(prefix = "logistic.readonly-datasource")
    public DataSource readOnlyDataSource(
            @Qualifier("readOnlyDataSourceProperties") DataSourceProperties readOnlyDataSourceProperties) {
        // url/username/password vêm do DataSourceProperties acima; o @ConfigurationProperties
        // nesta anotação serve só para aplicar connection-init-sql (setConnectionInitSql) no
        // HikariDataSource já construído.
        return readOnlyDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "readOnlyJdbcTemplate")
    public JdbcTemplate readOnlyJdbcTemplate(@Qualifier("readOnlyDataSource") DataSource readOnlyDataSource) {
        return new JdbcTemplate(readOnlyDataSource);
    }
}
