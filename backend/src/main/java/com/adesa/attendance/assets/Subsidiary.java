package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="subsidiaries")
class Subsidiary extends AssetRecord {
 String name;
 boolean active = true;
}
