import type { AttendanceHistory } from '../api/types';
import { formatTime, formatWorkday } from '../utils/format';
import { AttendanceStateBadge } from './StatusBadge';

/** The employee's own attendance history, newest workday first. */
export function HistoryTable({ history }: { history: AttendanceHistory | null }) {
  if (!history || history.records.length === 0) {
    return (
      <section className="card" aria-labelledby="history-heading">
        <h2 id="history-heading">History</h2>
        <p className="hint">No attendance records yet.</p>
      </section>
    );
  }

  return (
    <section className="card" aria-labelledby="history-heading">
      <h2 id="history-heading">History</h2>
      <div className="table-scroll">
        <table>
          <caption className="visually-hidden">Your attendance history</caption>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Status</th>
              <th scope="col">Signed in</th>
              <th scope="col">Signed out</th>
            </tr>
          </thead>
          <tbody>
            {history.records.map((record) => (
              <tr key={record.id}>
                <td>{formatWorkday(record.attendanceDate)}</td>
                <td>
                  <AttendanceStateBadge state={record.state} />
                </td>
                <td>{formatTime(record.signInAt)}</td>
                <td>{formatTime(record.signOutAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">
        {history.totalElements} record{history.totalElements === 1 ? '' : 's'} in total.
      </p>
    </section>
  );
}
