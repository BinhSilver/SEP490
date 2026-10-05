package com.jlas.kaiwa.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {
    @Bean
    @Profile("!test")
    public ApplicationRunner kaiwaMemorySchema(DataSource dataSource,
            @Value("${kaiwa.memory.initialize-schema:true}") boolean initialize) {
        return args -> {
            if (initialize) new ResourceDatabasePopulator(new ClassPathResource("db/kaiwa-memory.sql")).execute(dataSource);
        };
    }
}
