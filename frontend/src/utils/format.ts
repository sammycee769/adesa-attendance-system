/**
 * Date and time formatting.
 *
 * Everything is displayed in the attendance timezone (`Africa/Lagos` by
 * default, configurable on the server). The employee's device timezone is
 * never used: the workday is a server-side concept.
 */

export const ATTENDANCE_TIMEZONE = 'Africa/Lagos';

const dateFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: ATTENDANCE_TIMEZONE,
  weekday: 'long',
  day: '2-digit',
  month: 'long',
  year: 'numeric',
});

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: ATTENDANCE_TIMEZONE,
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

const dateTimeFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: ATTENDANCE_TIMEZONE,
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

/** The current date in the attendance timezone, e.g. "Wednesday, 21 October 2026". */
export function formatToday(now: Date = new Date()): string {
  return dateFormatter.format(now);
}

/** The time of an instant in the attendance timezone, e.g. "09:15". */
export function formatTime(instant: string | null): string {
  if (!instant) {
    return '—';
  }
  return timeFormatter.format(new Date(instant));
}

/** A workday (a plain date) rendered in the attendance timezone. */
export function formatWorkday(workday: string): string {
  if (!workday) {
    return '—';
  }
  const date = new Date(`${workday}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) {
    return workday;
  }
  return dateFormatter.format(date);
}

/** A timestamp in the attendance timezone, e.g. "21 Oct 2026, 09:15". */
export function formatDateTime(instant: string | null): string {
  if (!instant) {
    return '—';
  }
  return dateTimeFormatter.format(new Date(instant));
}

/** The current workday (e.g. "2026-10-21") in the attendance timezone. */
export function todayWorkday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: ATTENDANCE_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const value: Record<string, string> = {};
  for (const part of parts) {
    value[part.type] = part.value;
  }
  return `${value.year}-${value.month}-${value.day}`;
}

/**
 * The offset of a timezone at a given instant, in milliseconds.
 *
 * Used to turn a wall-clock time in the attendance timezone into an absolute
 * instant, without depending on the timezone of the browser.
 */
function zoneOffsetMillis(instant: Date, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(instant);
  const value: Record<string, string> = {};
  for (const part of parts) {
    value[part.type] = part.value;
  }
  const asUtc = Date.UTC(
    Number(value.year),
    Number(value.month) - 1,
    Number(value.day),
    Number(value.hour) % 24,
    Number(value.minute),
    Number(value.second),
  );
  return asUtc - instant.getTime();
}

/**
 * Turns a wall-clock time in the attendance timezone into an absolute instant.
 *
 * The input is the value of a `datetime-local` input, e.g.
 * "2026-03-05T08:00". The result is an ISO-8601 instant, which is what the
 * backend expects. The browser's own timezone is never used.
 */
export function attendanceLocalToInstant(local: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(local.trim());
  if (!match) {
    return null;
  }
  const [, year, month, day, hour, minute] = match;
  const wallClock = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
  // The offset is looked up twice, because it can change around a daylight
  // saving switch.
  let instant = wallClock - zoneOffsetMillis(new Date(wallClock), ATTENDANCE_TIMEZONE);
  instant = wallClock - zoneOffsetMillis(new Date(instant), ATTENDANCE_TIMEZONE);
  return new Date(instant).toISOString();
}

/**
 * Turns an absolute instant into a wall-clock time in the attendance timezone,
 * ready for a `datetime-local` input.
 */
export function instantToAttendanceLocal(instant: string | null): string {
  if (!instant) {
    return '';
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: ATTENDANCE_TIMEZONE,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(date);
  const value: Record<string, string> = {};
  for (const part of parts) {
    value[part.type] = part.value;
  }
  const hour = value.hour === '24' ? '00' : value.hour;
  return `${value.year}-${value.month}-${value.day}T${hour}:${value.minute}`;
}
