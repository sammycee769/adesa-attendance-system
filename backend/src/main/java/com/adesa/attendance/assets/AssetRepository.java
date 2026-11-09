package com.adesa.attendance.assets;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AssetRepository extends JpaRepository<Asset, UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select a from Asset a where a.id=:id")
 Optional<Asset> findLockedById(@Param("id") UUID id);
}
