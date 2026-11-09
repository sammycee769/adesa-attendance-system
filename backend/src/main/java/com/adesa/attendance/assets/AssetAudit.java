package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="asset_audit_logs")
class AssetAudit extends AssetRecord {
 UUID assetId;
 UUID employeeId;
 String action;
 String previousState;
 String newState;
 String notes;
}
