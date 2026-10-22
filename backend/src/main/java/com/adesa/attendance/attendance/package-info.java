/**
 * Attendance engine: sign-in/sign-out, daily status, history, and the
 * office-network restriction.
 *
 * <p>The {@code network} subpackage implements the configurable office-network
 * allowlist (client-address resolution via trusted proxies, CIDR matching, and
 * fail-closed enforcement).
 *
 * <p>The engine itself (Phase 5) owns the attendance records, the workday
 * calculation in the configured timezone, the transaction and concurrency
 * protection, and the audit trail of every accepted or refused action.
 */
package com.adesa.attendance.attendance;
