package com.handys.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReservationIntegrityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReservationIntegrityServiceApplication.class, args);
    }
}
