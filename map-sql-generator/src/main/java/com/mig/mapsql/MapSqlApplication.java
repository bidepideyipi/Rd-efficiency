package com.mig.mapsql;

import com.mig.mapsql.config.GeneratorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GeneratorProperties.class)
public class MapSqlApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapSqlApplication.class, args);
    }
}
