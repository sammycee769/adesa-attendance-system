import { describe, expect, it } from 'vitest';

import {
  attendanceLocalToInstant,
  instantToAttendanceLocal,
  todayWorkday,
} from './format';

/**
 * The conversion between the wall-clock time in the attendance timezone and
 * the absolute instant the backend stores.
 *
 * Africa/Lagos is UTC+1 without daylight saving, so the expected values are
 * stable.
 */
describe('attendance time conversion', () => {
  it('turns a wall-clock time of the attendance timezone into an instant', () => {
    expect(attendanceLocalToInstant('2026-10-20T08:30')).toBe('2026-10-20T07:30:00.000Z');
  });

  it('turns an instant into the wall-clock time of the attendance timezone', () => {
    expect(instantToAttendanceLocal('2026-10-20T07:30:00Z')).toBe('2026-10-20T08:30');
  });

  it('round-trips a time', () => {
    const instant = '2026-01-15T09:05:00Z';
    expect(attendanceLocalToInstant(instantToAttendanceLocal(instant))).toBe('2026-01-15T09:05:00.000Z');
  });

  it('rejects an incomplete value', () => {
    expect(attendanceLocalToInstant('not a date')).toBeNull();
    expect(attendanceLocalToInstant('')).toBeNull();
  });

  it('returns an empty value for a missing instant', () => {
    expect(instantToAttendanceLocal(null)).toBe('');
    expect(instantToAttendanceLocal('not a date')).toBe('');
  });

  it('reads the current workday in the attendance timezone', () => {
    // 23:30 UTC is already the next day in Lagos (UTC+1).
    expect(todayWorkday(new Date('2026-10-20T23:30:00Z'))).toBe('2026-10-21');
    expect(todayWorkday(new Date('2026-10-20T10:00:00Z'))).toBe('2026-10-20');
  });
});
