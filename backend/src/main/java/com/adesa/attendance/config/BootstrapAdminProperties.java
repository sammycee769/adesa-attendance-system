package com.adesa.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Controlled creation of the very first ADMIN account.
 *
 * <p>There is no public self-registration. The first administrator is created
 * at startup only when ADESA IT explicitly provides bootstrap values through
 * environment variables AND no ADMIN account exists yet. The password must be
 * at least 12 characters and is stored only as a hash; the bootstrap values
 * should be removed (unset) once the account exists.
 */
@Validated
@ConfigurationProperties(prefix = "adesa.bootstrap.admin")
public class BootstrapAdminProperties {

    private String employeeNumber = "";

    private String fullName = "";

    private String email = "";

    private String password = "";

    public boolean isRequested() {
        return !email.isBlank();
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public void setEmployeeNumber(String employeeNumber) {
        this.employeeNumber = employeeNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
