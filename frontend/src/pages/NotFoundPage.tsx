import { Link } from 'react-router-dom';

/** Shown for an unknown address. */
export function NotFoundPage() {
  return (
    <div className="dashboard">
      <section className="card">
        <h1>Page not found</h1>
        <p>The page you asked for does not exist.</p>
        <Link className="button button-primary" to="/">
          Back to the dashboard
        </Link>
      </section>
    </div>
  );
}
