package com.adesa.attendance.assets;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity
@Table(name="it_assets")
class Asset extends AssetRecord {
 String assetCode;
 String assetType;
 String brand;
 String model;
 String serialNumber;
 LocalDate purchaseDate;
 String status;
 String condition;
}
