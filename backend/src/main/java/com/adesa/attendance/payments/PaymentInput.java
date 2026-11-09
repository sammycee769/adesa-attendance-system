package com.adesa.attendance.payments;
import java.math.BigDecimal;
import jakarta.validation.constraints.*;
public record PaymentInput(
 @NotBlank @Size(max=150) String name,
 @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount,
 @NotNull @Pattern(regexp="NGN|USD") String currency,
 @NotNull @Size(max=100) String estimatedNgn,
 @Min(1) @Max(28) int scheduledDay,
 @Min(1) @Max(28) Integer renewalDay,
 @NotNull @Size(max=2000) String description,
 @NotBlank @Size(max=500) String toAddress,
 @NotNull @Size(max=2000) String ccAddresses,
 @NotBlank @Size(max=300) String subject,
 @NotBlank @Size(max=20000) String body,
 java.util.UUID ownerEmployeeId, boolean enabled, @Min(0) int version) {}
