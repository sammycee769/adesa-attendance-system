package com.adesa.attendance.assets;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;

/** HTTP contracts. Actors, timestamps and approval identity are always server-owned. */
public final class AssetRequests {
 private AssetRequests() {}
 public record DeviceRequestInput(@NotNull UUID assetId, @NotNull UUID subsidiaryId,
   @NotBlank @Size(max=4000) String reason) {}
 public record EmployeeRepairRequest(@NotNull UUID assetId,
   @NotBlank @Size(max=4000) String description, @Size(max=4000) String notes) {}
 public record Registration(@NotBlank @Pattern(regexp="LAPTOP|MOBILE_PHONE") String assetType,
   @NotBlank @Size(max=120) String brand, @NotBlank @Size(max=160) String model,
   @NotBlank @Size(max=160) String serialNumber, @NotNull @PastOrPresent LocalDate purchaseDate,
   @NotBlank @Pattern(regexp="NEW|GOOD|FAIR|DAMAGED|UNUSABLE") String condition) {}
 public record Edit(@NotBlank @Size(max=120) String brand, @NotBlank @Size(max=160) String model,
   @NotBlank @Size(max=160) String serialNumber, @NotNull @PastOrPresent LocalDate purchaseDate,
   @NotBlank @Pattern(regexp="NEW|GOOD|FAIR|DAMAGED|UNUSABLE") String condition,
   @NotNull long version) {}
 public record Assign(@NotNull UUID assetId, @NotNull UUID employeeId, @NotNull UUID subsidiaryId,
   @NotNull @PastOrPresent LocalDate assignmentDate, @Size(max=4000) String notes) {}
 public record Fault(@NotNull UUID assetId, @NotBlank @Size(max=200) String title,
   @NotBlank @Size(max=4000) String description,
   @NotBlank @Pattern(regexp="HARDWARE|SOFTWARE|BATTERY|SCREEN|KEYBOARD|NETWORK|PHYSICAL_DAMAGE|OTHER") String category,
   @NotBlank @Pattern(regexp="LOW|MEDIUM|HIGH|CRITICAL") String priority, @Size(max=4000) String notes) {}
 public record RepairRequest(@NotNull UUID assetId, UUID issueId, @NotBlank @Size(max=4000) String description,
   @NotBlank @Pattern(regexp="INTERNAL|EXTERNAL") String repairType, @Size(max=200) String technician,
   @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal estimatedCost, @Size(max=4000) String notes) {}
 public record ReplacementRequest(@NotNull UUID assetId,
   @NotBlank @Pattern(regexp="DAMAGED|FAULTY|OBSOLETE|PERFORMANCE_ISSUES|OTHER") String reason,
   @NotBlank @Size(max=4000) String description) {}
 public record ReturnRequest(@NotNull UUID assignmentId, @NotNull @FutureOrPresent LocalDate expectedDate,
   @Size(max=4000) String notes) {}
 public record ReturnReceipt(@NotNull @PastOrPresent LocalDate actualDate,
   @NotBlank @Pattern(regexp="NEW|GOOD|FAIR|DAMAGED|UNUSABLE") String condition,
   @NotBlank @Size(max=4000) String notes) {}
 public record ClearanceRequest(@NotNull UUID employeeId, @Size(max=4000) String notes) {}
 public record Decision(@NotBlank @Size(max=4000) String notes) {}
 public record Fulfil(@NotNull UUID replacementAssetId, @NotNull UUID subsidiaryId,
   @NotBlank @Size(max=4000) String notes) {}
 public record IssueUpdate(@NotBlank @Pattern(regexp="OPEN|UNDER_REVIEW|IN_PROGRESS|RESOLVED|CLOSED") String status,
   @NotBlank @Pattern(regexp="LOW|MEDIUM|HIGH|CRITICAL") String priority,
   @NotBlank @Size(max=4000) String notes) {}
 public record RepairUpdate(@NotBlank @Pattern(regexp="UNDER_REVIEW|APPROVED|IN_PROGRESS|COMPLETED|REJECTED|CANCELLED") String status,
   @Pattern(regexp="REPAIR|REPLACE|REJECT") String decision, @Size(max=200) String technician,
   @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal actualCost,
   @Pattern(regexp="NEW|GOOD|FAIR|DAMAGED|UNUSABLE") String condition,
   @NotBlank @Size(max=4000) String notes) {}
 public record SubsidiaryRequest(@NotBlank @Size(max=200) String name) {}
 public record EmployeeSubsidiary(@NotNull UUID subsidiaryId) {}
}
