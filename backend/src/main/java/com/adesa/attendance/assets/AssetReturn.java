package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="asset_returns")
class AssetReturn extends AssetRecord {
 UUID assetId;
 UUID employeeId;
 UUID assignmentId;
 LocalDate requestDate;
 LocalDate expectedDate;
 LocalDate actualDate;
 String condition;
 String status;
 UUID receivedBy;
 String notes;
}
