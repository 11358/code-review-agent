package com.cragent.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.cragent")
public class CrAgentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrAgentApiApplication.class, args);
    }
}
