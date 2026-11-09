package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="employee_clearance_items")
class ClearanceItem extends AssetRecord {
 UUID clearanceId;
 UUID assignmentId;
}
