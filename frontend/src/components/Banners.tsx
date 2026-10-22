interface ErrorBannerProps {
  /** The message shown to the employee. */
  message: string | null;
  /** Optional second line with a hint on what to do next. */
  hint?: string | null;
}

/**
 * Error and warning messages.
 *
 * `role="alert"` makes screen readers announce the message immediately.
 */
export function ErrorBanner({ message, hint }: ErrorBannerProps) {
  if (!message) {
    return null;
  }
  return (
    <div className="banner banner-error" role="alert">
      <p className="banner-message">{message}</p>
      {hint ? <p className="banner-hint">{hint}</p> : null}
    </div>
  );
}

interface SuccessBannerProps {
  message: string | null;
}

/** Confirmation messages for a completed action. */
export function SuccessBanner({ message }: SuccessBannerProps) {
  if (!message) {
    return null;
  }
  return (
    <div className="banner banner-success" role="status" aria-live="polite">
      <p className="banner-message">{message}</p>
    </div>
  );
}
