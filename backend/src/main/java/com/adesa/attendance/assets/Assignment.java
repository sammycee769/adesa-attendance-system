package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="asset_assignments")
class Assignment extends AssetRecord {
 UUID assetId;
 UUID employeeId;
 UUID subsidiaryId;
 LocalDate assignmentDate;
 String status;
 String approvalStatus;
 UUID approvedBy;
 Instant approvalDate;
 String notes;
}
