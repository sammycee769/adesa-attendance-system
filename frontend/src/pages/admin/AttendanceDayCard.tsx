import { useState, type MouseEvent } from 'react';
import type { AttendanceDay, DailyAttendanceRow, DailyAttendanceStatus } from '../../api/types';
import { formatTime } from '../../utils/format';

export const attendanceLabels: Record<DailyAttendanceStatus, string> = {
  PRESENT: 'Present',
  LATE: 'Late',
  ABSENT: 'Absent',
  NOT_YET_SIGNED_IN: 'Not Yet Signed In',
  SIGNED_OUT: 'Signed Out',
  INCOMPLETE_ATTENDANCE: 'Incomplete Attendance',
  ON_APPROVED_LEAVE: 'On Approved Leave',
  EXCUSED_ABSENCE: 'Excused Absence',
  REMOTE_WORK: 'Remote Work',
  NOT_SCHEDULED: 'Not Scheduled',
};

const longDate = new Intl.DateTimeFormat('en-GB', {
  weekday: 'long', year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC',
});

function workedTime(minutes: number | null) {
  return minutes === null ? '–' : `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
}

function tone(status: DailyAttendanceStatus) {
  if (status === 'ABSENT') return 'absent';
  if (status === 'LATE' || status === 'INCOMPLETE_ATTENDANCE') return 'attention';
  return status === 'PRESENT' || status === 'SIGNED_OUT' ? 'present' : 'neutral';
}

export function AttendanceDayCard({ day, initiallyOpen, onCorrect }: {
  day: AttendanceDay;
  initiallyOpen: boolean;
  onCorrect: (row: DailyAttendanceRow, event: MouseEvent<HTMLButtonElement>) => void;
}) {
  const [open, setOpen] = useState(initiallyOpen);
  const title = longDate.format(new Date(`${day.date}T00:00:00Z`));
  const titleId = `attendance-heading-${day.date}`;
  const panelId = `attendance-panel-${day.date}`;
  const s = day.summary;

  return (
    <section className="card attendance-day" aria-labelledby={titleId}>
      <div className="attendance-day-header">
        <h2 id={titleId}>
          <button className="attendance-day-toggle" type="button" aria-expanded={open}
            aria-controls={panelId} onClick={() => setOpen(!open)}>
            {title}<span className="attendance-chevron" aria-hidden="true" />
          </button>
        </h2>
        <p className="attendance-day-counts">
          <strong className="attendance-present">{s.present} present</strong>
          <strong className="attendance-absent">{s.absent} absent</strong>
          <span>{s.expected} expected</span>
        </p>
        <p className="attendance-day-detail">
          <span>{s.late} late</span><span>{s.signedOut} signed out</span><span>{s.yetToSignOut} yet to sign out</span>
          <span>{s.incomplete} incomplete</span><span>{s.approvedAbsences} on leave or excused</span>
        </p>
      </div>
      <div id={panelId} hidden={!open}>
        {open && <div className="table-scroll">
          <table className="attendance-day-table">
            <caption className="visually-hidden">Employee attendance for {title}</caption>
            <thead><tr>{['Employee', 'Subsidiary', 'Department', 'Sign in', 'Sign out', 'Hours worked',
              'Attendance status', 'Remarks', 'Action'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
            <tbody>{day.employees.map(row => <tr key={row.employeeId}>
              <th scope="row">{row.employeeName}{row.employeeNumber && <span className="cell-note"> {row.employeeNumber}</span>}</th>
              <td>{row.subsidiary || '–'}</td>
              <td>{row.department || '–'}</td>
              <td className="attendance-clock">{row.signInAt ? formatTime(row.signInAt) : '–'}</td>
              <td className="attendance-clock">{row.signOutAt ? formatTime(row.signOutAt) : '–'}</td>
              <td className="attendance-clock">{workedTime(row.workedMinutes)}</td>
              <td><span className={`attendance-badge attendance-badge--${tone(row.status)}`}>{attendanceLabels[row.status]}</span></td>
              <td>{row.remarks || '–'}</td>
              <td>{row.recordId ? <button className="button button-secondary" type="button"
                aria-label={`Correct attendance for ${row.employeeName}`} onClick={event => onCorrect(row,event)}>Correct</button> : '–'}</td>
            </tr>)}</tbody>
          </table>
        </div>}
      </div>
    </section>
  );
}
