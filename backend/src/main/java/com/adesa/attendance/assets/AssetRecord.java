package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
/** Shared persistence metadata; workflows always retain historical records. */
@MappedSuperclass
abstract class AssetRecord {
 @Id UUID id = UUID.randomUUID();
 @Version long version;
 Instant createdAt = Instant.now();
 Instant updatedAt = Instant.now();
 UUID createdBy;
 UUID updatedBy;
 @PreUpdate void updated() { updatedAt = Instant.now(); }
}
