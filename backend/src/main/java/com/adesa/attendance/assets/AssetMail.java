package com.adesa.attendance.assets;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_mail_outbox")
class AssetMail extends AssetRecord {
    UUID assetId;
    UUID requestId;
    String eventType;
    String recipientEmail;
    String subject;
    String textBody;
    String status = "PENDING";
    int attempts;
    Instant nextAttemptAt = Instant.now();
    Instant deliveredAt;
}
