package com.adesa.attendance.attendance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, UUID> {

    /** Number of corrections per attendance record, for the "corrected" marker. */
    @Query("select c.attendanceRecordId, count(c) from AttendanceCorrection c "
            + "where c.attendanceRecordId in :recordIds group by c.attendanceRecordId")
    List<Object[]> countByRecordIds(@Param("recordIds") List<UUID> recordIds);

    List<AttendanceCorrection> findByAttendanceRecordIdOrderByCreatedAtDesc(UUID attendanceRecordId);
}
