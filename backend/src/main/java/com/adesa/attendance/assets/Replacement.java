package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="asset_replacement_requests")
class Replacement extends AssetRecord {
 UUID assetId;
 UUID employeeId;
 String reason;
 String description;
 String status;
 UUID reviewedBy;
 Instant approvalDate;
 String notes;
 UUID replacementAssetId;
}
