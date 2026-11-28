package com.mig.mapsql;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.config.MigDataSourceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableConfigurationProperties({GeneratorProperties.class, MigDataSourceProperties.class})
public class MapSqlApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapSqlApplication.class, args);
    }
}
