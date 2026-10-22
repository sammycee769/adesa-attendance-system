package com.adesa.attendance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ADESA HQ Attendance Management System.
 *
 * <p>Modular monolith: feature packages under {@code com.adesa.attendance}
 * (auth, employee, device, attendance, admin, audit) plus config, security
 * and common infrastructure.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdesaAttendanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdesaAttendanceApplication.class, args);
    }
}
