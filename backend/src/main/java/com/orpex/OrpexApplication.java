package com.orpex;

import com.orpex.config.OrpexProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OrpexProperties.class)
public class OrpexApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrpexApplication.class, args);
    }
}
