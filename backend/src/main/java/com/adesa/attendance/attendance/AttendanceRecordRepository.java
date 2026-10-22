package com.adesa.attendance.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Attendance records. Every query is scoped to a single employee: an employee
 * can only ever reach their own records.
 */
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID>,
        JpaSpecificationExecutor<AttendanceRecord> {

    Optional<AttendanceRecord> findByEmployeeIdAndAttendanceDate(UUID employeeId, LocalDate attendanceDate);

    /**
     * Loads the employee's record for a workday under a write lock.
     *
     * <p>The lock serialises concurrent sign-out requests: the second
     * transaction waits and then sees the committed sign-out, so a workday can
     * never be closed twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AttendanceRecord a where a.employeeId = :employeeId "
            + "and a.attendanceDate = :attendanceDate")
    Optional<AttendanceRecord> findForUpdate(@Param("employeeId") UUID employeeId,
                                             @Param("attendanceDate") LocalDate attendanceDate);

    /**
     * Loads a record under a write lock so a correction cannot race with
     * another correction or with a sign-out of the same workday.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AttendanceRecord a where a.id = :id")
    Optional<AttendanceRecord> findByIdForUpdate(@Param("id") UUID id);

    Page<AttendanceRecord> findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(
            UUID employeeId, LocalDate from, LocalDate to, Pageable pageable);

    /** All records of one workday, for the daily overview. */
    List<AttendanceRecord> findByAttendanceDate(LocalDate attendanceDate);
}
