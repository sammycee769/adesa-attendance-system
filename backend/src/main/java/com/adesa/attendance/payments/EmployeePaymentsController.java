package com.adesa.attendance.payments;
import com.adesa.attendance.security.AuthenticatedEmployee;
import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/recurring-payments")
@PreAuthorize("isAuthenticated()")
public class EmployeePaymentsController {
 private final PaymentService service;
 public EmployeePaymentsController(PaymentService service){this.service=service;}
 @GetMapping public Map<String,Object> dashboard(Authentication authentication){return service.employeeDashboard(AuthenticatedEmployee.from(authentication),Instant.now());}
 @PostMapping("/{id}/preview") public PaymentComposer.Message preview(@PathVariable UUID id,@Valid @RequestBody PaymentController.Month month,Authentication authentication){
  return service.previewFor(id,YearMonth.parse(month.month()),AuthenticatedEmployee.from(authentication));
 }
}
