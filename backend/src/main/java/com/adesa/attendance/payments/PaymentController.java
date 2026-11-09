package com.adesa.attendance.payments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/recurring-payments")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentController {
 private final PaymentService service;
 public PaymentController(PaymentService service){this.service=service;}
 @GetMapping public Map<String,Object> dashboard(){return service.dashboard(Instant.now());}
 @PostMapping public Map<String,UUID> create(@Valid @RequestBody PaymentInput input,Principal principal){return Map.of("id",service.create(input,principal.getName()));}
 @PutMapping("/{id}") public void save(@PathVariable UUID id,@Valid @RequestBody PaymentInput input,Principal principal){service.save(id,input,principal.getName());}
 public record Settings(boolean enabled,@NotNull @Size(max=100000) String signatureHtml) {}
 @PutMapping("/settings") public void settings(@Valid @RequestBody Settings input,Principal principal){service.settings(input.enabled(),input.signatureHtml(),principal.getName());}
 public record Month(@NotNull @Pattern(regexp="20[0-9]{2}-(0[1-9]|1[0-2])") String month){}
 @PostMapping("/{id}/preview") public PaymentComposer.Message preview(@PathVariable UUID id,@Valid @RequestBody Month month){return service.preview(id,YearMonth.parse(month.month()));}
 @PostMapping("/{id}/generate") public Map<String,UUID> generate(@PathVariable UUID id,@Valid @RequestBody Month month,Principal principal){return Map.of("id",service.generate(id,YearMonth.parse(month.month()),principal.getName()));}
 public record Recovery(@NotBlank @Size(max=1000) String reason){}
 @PostMapping("/requests/{id}/recover") public void recover(@PathVariable UUID id,@Valid @RequestBody Recovery input,Principal principal){service.recover(id,principal.getName(),input.reason(),Instant.now());}
 public record Reconciliation(boolean accepted,@NotNull @Size(max=300) String providerId,@NotBlank @Size(max=1000) String reason){}
 @PostMapping("/requests/{id}/reconcile") public void reconcile(@PathVariable UUID id,@Valid @RequestBody Reconciliation input,Principal principal){service.reconcile(id,input.accepted(),input.providerId(),input.reason(),principal.getName());}
}
