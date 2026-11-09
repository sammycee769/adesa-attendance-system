package com.adesa.attendance.assets;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_requests")
class DeviceRequest extends AssetRecord {
    UUID assetId;
    UUID employeeId;
    UUID subsidiaryId;
    String reason;
    String status;
    UUID assignmentId;
    UUID reviewedBy;
    Instant reviewedAt;
    String notes;
}
