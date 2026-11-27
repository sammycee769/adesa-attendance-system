import type { FormEvent } from "react";
export type EventDraft = Record<string, any>;
const gameNames: Record<string, string> = {
  SPIN: "Spin & Win",
  MATCH: "Match & Win",
  SCRATCH: "Scratch & Win",
  TRIVIA: "ADESA Trivia",
};
const categoryNames: Record<string, string> = {
  REGULAR: "Regular rewards",
  CONSULTATION: "Consultations & assessments",
  PREMIUM: "Premium rewards",
  GIFT: "Available gifts",
  GRAND: "Grand prizes",
};
export function localDateTime(value: string) {
  const date = new Date(value);
  if (!value || Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
export function utcDateTime(value: string) {
  return value ? new Date(value).toISOString() : "";
}
function readableDate(value: string) {
  const d = new Date(value);
  return Number.isNaN(d.getTime())
    ? "Choose a date and time"
    : d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}
function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: React.ReactNode;
  hint?: string;
}) {
  return (
    <label className="em-field">
      {label}
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}
export function EventEditor({
  draft,
  onChange,
  selectedEvent,
  prizes,
  busy,
  onNew,
  onLoad,
  onSave,
  onToggle,
}: {
  draft: EventDraft;
  onChange: (v: EventDraft) => void;
  selectedEvent?: EventDraft;
  prizes: EventDraft[];
  busy: boolean;
  onNew: () => void;
  onLoad: () => void;
  onSave: () => void;
  onToggle: () => void;
}) {
  const config = draft.config;
  const field = (key: string, value: unknown) =>
    onChange({ ...draft, [key]: value });
  const setting = (key: string, value: unknown) =>
    field("config", { ...config, [key]: value });
  const questions: EventDraft[] = config.questions || [];
  const question = (index: number, key: string, value: unknown) =>
    setting(
      "questions",
      questions.map((q, i) => (i === index ? { ...q, [key]: value } : q)),
    );
  const approvedQuestions = (selectedEvent?.config.questions || []).filter(
    (q: EventDraft) => q.approved,
  ).length;
  const inventory = prizes.filter(
    (p) => p.active && p.approved && p.remaining > 0,
  );
  const ready =
    selectedEvent &&
    selectedEvent.approved &&
    inventory.length > 0 &&
    (!selectedEvent.config.games.includes("TRIVIA") ||
      approvedQuestions >= 5) &&
    Date.parse(selectedEvent.ends_at) > Date.now();
  const locked = Boolean(draft.id && draft.active);
  const unsaved = Boolean(
    draft.id === selectedEvent?.id &&
    JSON.stringify(draft) !== JSON.stringify(selectedEvent),
  );
  const total = Object.values(config.weights as Record<string, number>).reduce(
    (sum, n) => sum + Number(n),
    0,
  );
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  function submit(e: FormEvent) {
    e.preventDefault();
    onSave();
  }
  return (
    <div className="em-editor">
      <section className="em-card em-event-status">
        <div>
          <p className="em-eyebrow">EVENT STATUS</p>
          <h2>{selectedEvent?.name || "Create your first event"}</h2>
          {selectedEvent && (
            <p>
              <span
                className={`em-status-pill ${selectedEvent.active ? "is-live" : ""}`}
              >
                {selectedEvent.active ? "Active" : "Inactive"}
              </span>{" "}
              {readableDate(selectedEvent.starts_at)} –{" "}
              {readableDate(selectedEvent.ends_at)}
            </p>
          )}
        </div>
        <div className="em-actions">
          {selectedEvent && (
            <button
              disabled={busy || (!selectedEvent.active && (!ready || unsaved))}
              onClick={onToggle}
            >
              {selectedEvent.active ? "Deactivate event" : "Activate event"}
            </button>
          )}
          <button className="em-button-light" disabled={busy} onClick={onNew}>
            New event
          </button>
        </div>
        {selectedEvent && !selectedEvent.active && (
          <div className="em-readiness">
            <strong>Before you activate</strong>
            <ul>
              <li>
                {selectedEvent.approved ? "✓" : "○"} Rules and privacy notice
                approved
              </li>
              <li>
                {inventory.length ? "✓" : "○"} Approved prizes with available
                stock — manage these in Prizes
              </li>
              <li>
                {!selectedEvent.config.games.includes("TRIVIA") ||
                approvedQuestions >= 5
                  ? "✓"
                  : "○"}{" "}
                Five approved questions if Trivia is enabled
              </li>
              <li>
                {Date.parse(selectedEvent.ends_at) > Date.now() ? "✓" : "○"} The
                event end date is still ahead
              </li>
            </ul>
          </div>
        )}
        {unsaved && !selectedEvent?.active && (
          <p className="em-small">
            Save your changes below before activating this event.
          </p>
        )}
        {selectedEvent?.active &&
          Date.parse(selectedEvent.starts_at) > Date.now() && (
            <p className="em-small">
              Registration opens on {readableDate(selectedEvent.starts_at)}.
            </p>
          )}
      </section>
      <form className="em-editor-form" onSubmit={submit}>
        <section className="em-card">
          <div className="em-editor-heading">
            <div>
              <h2>{draft.id ? "Edit event" : "New event details"}</h2>
              <p>
                {locked
                  ? "Deactivate this event above to edit its settings."
                  : "Save your settings below, approve your prizes, then activate the event above."}
              </p>
            </div>
            {selectedEvent && draft.id !== selectedEvent.id && (
              <button
                type="button"
                className="em-button-light"
                onClick={onLoad}
              >
                Edit selected event
              </button>
            )}
          </div>
          <fieldset disabled={busy || locked} className="em-editor-fields">
            <Field label="Event name">
              <input
                required
                maxLength={160}
                value={draft.name}
                onChange={(e) => field("name", e.target.value)}
                placeholder="ADESA business gathering"
              />
            </Field>
            <Field label="Description">
              <textarea
                maxLength={1200}
                value={draft.description}
                onChange={(e) => field("description", e.target.value)}
                rows={3}
              />
            </Field>
            <Field
              label="Event link name"
              hint="Use lowercase words separated by hyphens. This becomes the last part of the campaign link."
            >
              <input
                required
                readOnly={Boolean(draft.id)}
                pattern="[a-z0-9]+(-[a-z0-9]+)*"
                maxLength={80}
                value={draft.slug}
                onChange={(e) => field("slug", e.target.value)}
                placeholder="business-gathering"
              />
            </Field>
            <div className="em-columns">
              <Field label="Start date and time">
                <input
                  required
                  type="datetime-local"
                  value={localDateTime(draft.starts_at)}
                  onChange={(e) =>
                    field("starts_at", utcDateTime(e.target.value))
                  }
                />
              </Field>
              <Field label="End date and time">
                <input
                  required
                  type="datetime-local"
                  min={localDateTime(draft.starts_at)}
                  value={localDateTime(draft.ends_at)}
                  onChange={(e) =>
                    field("ends_at", utcDateTime(e.target.value))
                  }
                />
              </Field>
            </div>
            <p className="em-small">
              Times are shown in {timezone}. {readableDate(draft.starts_at)} to{" "}
              {readableDate(draft.ends_at)}.
            </p>
          </fieldset>
        </section>
        <section className="em-card">
          <h2>Games & rewards</h2>
          <fieldset disabled={busy || locked} className="em-editor-fields">
            <div>
              <p className="em-field-title">
                Choose the games participants can play
              </p>
              <div className="em-game-options">
                {Object.entries(gameNames).map(([id, name]) => (
                  <label key={id}>
                    <input
                      type="checkbox"
                      checked={config.games.includes(id)}
                      onChange={(e) =>
                        setting(
                          "games",
                          e.target.checked
                            ? [...config.games, id]
                            : config.games.filter((v: string) => v !== id),
                        )
                      }
                    />
                    {name}
                  </label>
                ))}
              </div>
            </div>
            <div className="em-columns">
              <Field label="Voucher validity (days)">
                <input
                  required
                  type="number"
                  min={1}
                  max={365}
                  value={config.validityDays}
                  onChange={(e) =>
                    setting("validityDays", Number(e.target.value))
                  }
                />
              </Field>
              <Field
                label="Low-stock alert threshold"
                hint="Notify staff when this many rewards or fewer remain."
              >
                <input
                  required
                  type="number"
                  min={0}
                  max={100000}
                  value={config.lowStockThreshold}
                  onChange={(e) =>
                    setting("lowStockThreshold", Number(e.target.value))
                  }
                />
              </Field>
            </div>
            <details className="em-settings-detail">
              <summary>Adjust prize draw chances</summary>
              <p>
                Give each category a share of the draw. Chances are calculated
                automatically from these shares. These are winning
                probabilities, never customer discounts.
              </p>
              <div className="em-chances">
                {Object.entries(categoryNames).map(([id, name]) => (
                  <Field
                    key={id}
                    label={name}
                    hint={`${total > 0 ? ((100 * Number(config.weights[id])) / total).toFixed(1) : 0}% chance when all categories are available`}
                  >
                    <input
                      required
                      type="number"
                      min={0}
                      max={10000}
                      value={config.weights[id]}
                      onChange={(e) =>
                        setting("weights", {
                          ...config.weights,
                          [id]: Number(e.target.value),
                        })
                      }
                    />
                  </Field>
                ))}
              </div>
            </details>
          </fieldset>
        </section>
        <section className="em-card">
          <h2>Campaign link & notifications</h2>
          <fieldset disabled={busy || locked} className="em-editor-fields">
            <Field
              label="Campaign website address"
              hint="The website hosting the registration page. Use its public address before printing QR codes."
            >
              <input
                required
                type="url"
                value={config.publicUrl}
                placeholder="https://adesahq.com"
                onChange={(e) =>
                  setting("publicUrl", e.target.value.replace(/\/$/, ""))
                }
              />
            </Field>
            <Field label="Staff notification email">
              <input
                type="email"
                value={config.notifyEmail}
                onChange={(e) => setting("notifyEmail", e.target.value)}
              />
            </Field>
          </fieldset>
        </section>
        <section className="em-card">
          <h2>Participation terms & privacy</h2>
          <fieldset disabled={busy || locked} className="em-editor-fields">
            <Field
              label="Terms version"
              hint="Change this when you update the participation terms."
            >
              <input
                required
                maxLength={40}
                value={config.termsVersion}
                onChange={(e) => setting("termsVersion", e.target.value)}
              />
            </Field>
            <Field label="Participation terms and privacy notice">
              <textarea
                required
                maxLength={5000}
                rows={6}
                value={config.terms}
                onChange={(e) => setting("terms", e.target.value)}
              />
            </Field>
            <label className="em-approval">
              <input
                type="checkbox"
                checked={draft.approved}
                onChange={(e) => field("approved", e.target.checked)}
              />{" "}
              Management has approved these rules and the privacy notice
            </label>
          </fieldset>
        </section>
        {config.games.includes("TRIVIA") && (
          <section className="em-card">
            <div className="em-editor-heading">
              <div>
                <h2>Trivia questions</h2>
                <p>
                  {questions.filter((q) => q.approved).length} approved
                  questions. At least five are needed to activate Trivia.
                </p>
              </div>
            </div>
            <fieldset disabled={busy || locked} className="em-editor-fields">
              {questions.map((q, index) => (
                <details key={index} className="em-question-editor">
                  <summary>
                    Question {index + 1}: {q.text || "New question"}{" "}
                    <span>{q.approved ? "Approved" : "Needs approval"}</span>
                  </summary>
                  <div className="em-editor-fields">
                    <Field label={`Question ${index + 1}`}>
                      <input
                        required
                        maxLength={300}
                        value={q.text}
                        onChange={(e) =>
                          question(index, "text", e.target.value)
                        }
                      />
                    </Field>
                    <div className="em-columns">
                      {q.options.map((option: string, answer: number) => (
                        <Field
                          key={answer}
                          label={`Question ${index + 1}, answer ${String.fromCharCode(65 + answer)}`}
                        >
                          <input
                            required
                            maxLength={200}
                            value={option}
                            onChange={(e) =>
                              question(
                                index,
                                "options",
                                q.options.map((v: string, i: number) =>
                                  i === answer ? e.target.value : v,
                                ),
                              )
                            }
                          />
                        </Field>
                      ))}
                    </div>
                    <Field label={`Correct answer for question ${index + 1}`}>
                      <select
                        value={q.correct}
                        onChange={(e) =>
                          question(index, "correct", Number(e.target.value))
                        }
                      >
                        {q.options.map((option: string, i: number) => (
                          <option value={i} key={i}>
                            {String.fromCharCode(65 + i)} —{" "}
                            {option || "Enter answer above"}
                          </option>
                        ))}
                      </select>
                    </Field>
                    <Field label={`Verified source for question ${index + 1}`}>
                      <input
                        required
                        maxLength={500}
                        value={q.source}
                        onChange={(e) =>
                          question(index, "source", e.target.value)
                        }
                        placeholder="Approved business information or reference"
                      />
                    </Field>
                    <label>
                      <input
                        type="checkbox"
                        checked={q.approved}
                        onChange={(e) =>
                          question(index, "approved", e.target.checked)
                        }
                      />{" "}
                      Question and correct answer approved
                    </label>
                    <button
                      type="button"
                      className="em-button-light"
                      onClick={() =>
                        setting(
                          "questions",
                          questions.filter((_, i) => i !== index),
                        )
                      }
                    >
                      Remove question {index + 1}
                    </button>
                  </div>
                </details>
              ))}
              <button
                type="button"
                className="em-button-light"
                onClick={() =>
                  setting("questions", [
                    ...questions,
                    {
                      text: "",
                      options: ["", "", "", ""],
                      correct: 0,
                      source: "",
                      approved: false,
                    },
                  ])
                }
              >
                Add question
              </button>
            </fieldset>
          </section>
        )}
        <div className="em-save-bar">
          <p>
            {locked
              ? "This event is active. Deactivate it before making changes."
              : "Saving does not activate an event. Use Activate event when everything is ready."}
          </p>
          <button disabled={busy || locked}>
            {busy ? "Saving…" : "Save event settings"}
          </button>
        </div>
      </form>
    </div>
  );
}
