package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="employee_asset_clearances")
class Clearance extends AssetRecord {
 UUID employeeId;
 String status;
 Instant clearanceDate;
 UUID clearedBy;
 String notes;
}
