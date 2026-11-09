package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="asset_repairs")
class Repair extends AssetRecord {
 UUID assetId;
 UUID employeeId;
 UUID issueId;
 String description;
 String repairType;
 String status;
 String decision;
 String technician;
 BigDecimal estimatedCost;
 BigDecimal actualCost;
 LocalDate submittedDate;
 LocalDate approvedDate;
 LocalDate startDate;
 LocalDate completionDate;
 UUID approvedBy;
 String notes;
}
