package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="asset_issues")
class Issue extends AssetRecord {
 UUID assetId;
 UUID employeeId;
 String title;
 String description;
 String category;
 String priority;
 String status;
 String notes;
}
