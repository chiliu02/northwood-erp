package com.northwood.manufacturing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ManufacturingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ManufacturingApplication.class, args);
    }
}
