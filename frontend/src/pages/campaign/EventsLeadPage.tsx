import { useEffect, useState } from "react";
import { apiRequest, apiDownload } from "../../api/client";
import "./campaign-admin.css";
import { EventEditor } from "./EventEditor";
type Json = Record<string, any>; // Server DTOs include configurable JSON event rules and question arrays.
const base = "/api/v1/campaign/staff";
const emptyPrize = {
  business: "ENERGY",
  name: "",
  category: "REGULAR",
  value_ngn: null,
  quantity: 0,
  approved: false,
  active: false,
  terms: "",
};
const blankConfig = {
  games: ["SPIN", "MATCH", "SCRATCH", "TRIVIA"],
  weights: { REGULAR: 40, CONSULTATION: 25, PREMIUM: 20, GIFT: 10, GRAND: 5 },
  validityDays: 90,
  lowStockThreshold: 3,
  termsVersion: "event-v1",
  publicUrl: "https://adesahq.com",
  notifyEmail: "",
  terms:
    "One official attempt per person per event. Rewards are single-use, non-transferable and subject to approved availability, eligibility and expiry. Personal data is used for event administration and redemption. Future marketing requires separate optional consent.",
  questions: [],
};
function initialEvent() {
  return {
    slug: "",
    name: "",
    description: "",
    starts_at: new Date().toISOString(),
    ends_at: new Date(Date.now() + 86400000).toISOString(),
    active: false,
    approved: false,
    config: structuredClone(blankConfig),
  };
}
function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="em-field">
      {label}
      {children}
    </label>
  );
}
function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function EventsLeadPage() {
  const [access, setAccess] = useState<Json | null>(null),
    [events, setEvents] = useState<Json[]>([]),
    [selected, setSelected] = useState(""),
    [dashboard, setDashboard] = useState<Json | null>(null),
    [participants, setParticipants] = useState<Json[]>([]),
    [tab, setTab] = useState("Dashboard"),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [eventDraft, setEventDraft] = useState<Json>(initialEvent),
    [prize, setPrize] = useState<Json>({ ...emptyPrize }),
    [search, setSearch] = useState(""),
    [business, setBusiness] = useState("ALL"),
    [game, setGame] = useState("ALL"),
    [consent, setConsent] = useState("ALL"),
    [conversion, setConversion] = useState("ALL"),
    [voucherCode, setVoucherCode] = useState(""),
    [voucher, setVoucher] = useState<Json | null>(null),
    [reference, setReference] = useState(""),
    [redeemBusiness, setRedeemBusiness] = useState("ENERGY"),
    [messages, setMessages] = useState<Json[]>([]),
    [audit, setAudit] = useState<Json[]>([]),
    [staffEmail, setStaffEmail] = useState(""),
    [capability, setCapability] = useState("VIEW");
  const event = events.find((e) => e.id === selected);
  async function refresh(id = selected, syncEditor = false) {
    const list = await apiRequest<Json[]>(`${base}/events`);
    setEvents(list);
    const useId = id || list[0]?.id;
    if (useId) {
      setSelected(useId);
      if (syncEditor) {
        const fresh = list.find((e) => e.id === useId);
        if (fresh) setEventDraft(structuredClone(fresh));
      }
      const [d, p] = await Promise.all([
        apiRequest<Json>(`${base}/events/${useId}/dashboard`),
        apiRequest<Json[]>(`${base}/events/${useId}/participants`),
      ]);
      setDashboard(d);
      setParticipants(p);
    }
  }
  useEffect(() => {
    const controller = new AbortController();
    apiRequest<Json>(`${base}/access`, { signal: controller.signal })
      .then((a) => {
        if (!controller.signal.aborted) {
          setAccess(a);
          void refresh().catch((e) => setError(e.message));
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e.message);
      });
    return () => controller.abort();
  }, []);
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Request failed.");
    } finally {
      setBusy(false);
    }
  }
  function editEvent(e: Json) {
    setEventDraft(structuredClone(e));
    setTab("Events");
  }
  async function openTab(name: string) {
    setTab(name);
    if (name === "Events" && event) editEvent(event);
    if (!selected) return;
    if (name === "Messages")
      await run(async () =>
        setMessages(
          await apiRequest<Json[]>(`${base}/events/${selected}/messages`),
        ),
      );
    if (name === "Audit")
      await run(async () =>
        setAudit(await apiRequest<Json[]>(`${base}/events/${selected}/audit`)),
      );
  }
  const filtered = participants.filter(
    (p) =>
      (business === "ALL" || p.interest === business) &&
      (game === "ALL" || p.game === game) &&
      (consent === "ALL" || String(p.marketing_consent) === consent) &&
      (conversion === "ALL" || String(p.converted) === conversion) &&
      `${p.full_name} ${p.email} ${p.phone} ${p.company} ${p.code || ""}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  const totals = dashboard?.vouchers || {},
    gamesPlayed = (dashboard?.byGame || []).reduce(
      (sum: number, g: Json) => sum + Number(g.count),
      0,
    );
  return (
    <div className="em-root">
      <div className="em-heading">
        <div>
          <p className="em-eyebrow">ADESA HQ – HOME</p>
          <h1>Events & Lead Management</h1>
          <p>Play, Connect & Win · Energy, Media & Services</p>
        </div>
        <div className="em-actions">
          <select
            aria-label="Select event"
            value={selected}
            onChange={(e) => {
              const id = e.target.value;
              if (tab === "Events") {
                const next = events.find((v) => v.id === id);
                if (next) editEvent(next);
              }
              void run(() => refresh(id));
            }}
          >
            <option value="">Select event</option>
            {events.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
                {e.demo ? " · DEMO" : ""}
              </option>
            ))}
          </select>
          <button disabled={busy} onClick={() => run(() => refresh())}>
            Refresh
          </button>
        </div>
      </div>
      {error && (
        <p role="alert" className="em-error">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="em-notice">
          {notice}
        </p>
      )}
      {!access ? (
        <p>Authorised campaign access is required.</p>
      ) : (
        <>
          {event?.demo && (
            <div className="em-demo">
              LOCAL DEMONSTRATION · Sample inventory and synthetic .test
              participants only. Emails remain captured previews. Demo rewards
              have no real value.
            </div>
          )}
          <nav className="em-tabs" aria-label="Campaign sections">
            {[
              "Dashboard",
              "Participants",
              "Prizes",
              "Redemption",
              ...(access.manage
                ? ["Events", "Messages", "Audit", "Permissions"]
                : []),
            ].map((name) => (
              <button
                key={name}
                aria-current={tab === name ? "page" : undefined}
                onClick={() => openTab(name)}
              >
                {name}
              </button>
            ))}
          </nav>
          {tab === "Dashboard" && dashboard && (
            <>
              <div className="em-stats">
                {[
                  ["Registrations", dashboard.registrations],
                  ["Games played", gamesPlayed],
                  ["Rewards issued", totals.issued],
                  ["Redeemed", totals.redeemed],
                  ["Unredeemed", totals.unredeemed],
                  ["Expired", totals.expired],
                  [
                    "Redemption rate",
                    totals.issued
                      ? `${((100 * totals.redeemed) / totals.issued).toFixed(1)}%`
                      : "0%",
                  ],
                  ["Paying customers", dashboard.converted],
                  [
                    "Available rewards",
                    (dashboard.prizes || [])
                      .filter((p: Json) => p.active && p.approved)
                      .reduce((n: number, p: Json) => n + p.remaining, 0),
                  ],
                ].map(([label, value]) => (
                  <div key={String(label)}>
                    <span>{label}</span>
                    <strong>{value ?? 0}</strong>
                  </div>
                ))}
              </div>
              <div className="em-columns">
                <section className="em-card">
                  <h2>Participation</h2>
                  {[
                    ...(dashboard.byBusiness || []),
                    ...(dashboard.byGame || []),
                  ].map((g: Json) => (
                    <p key={g.interest || g.game}>
                      <strong>{g.interest || g.game}</strong>
                      <span className="em-right">{g.count}</span>
                    </p>
                  ))}
                </section>
                <section className="em-card">
                  <h2>Event QR code</h2>
                  <p>
                    The public link opens registration on the ADESA website.
                  </p>
                  {event && (
                    <>
                      <img
                        className="em-qr-preview"
                        src={`${event.config.publicUrl}/api/campaign/qr?event=${encodeURIComponent(event.slug)}&inline=1`}
                        alt={`Registration QR code for ${event.name}`}
                        width={256}
                        height={256}
                      />
                      <a
                        href={`${event.config.publicUrl}/play/${event.slug}/qr`}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Open full-size QR display ↗
                      </a>
                      <br />
                      <a
                        href={`${event.config.publicUrl}/play/${event.slug}`}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Preview campaign ↗
                      </a>
                      <br />
                      <a
                        href={`${event.config.publicUrl}/api/campaign/qr?event=${event.slug}`}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Download print-ready QR (SVG) ↗
                      </a>
                      <p className="em-small">
                        Verify the deployed public origin before printing. Test
                        with an iPhone and Android camera.
                      </p>
                    </>
                  )}
                  {access.manage && (
                    <button
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          await apiRequest(`${base}/maintenance`, {
                            method: "POST",
                          });
                          await refresh();
                          setNotice(
                            "Timed-out games concluded and expiry reminders queued. Demo emails remain previews.",
                          );
                        })
                      }
                    >
                      Process expiry & timed-out games
                    </button>
                  )}
                </section>
              </div>
              <section className="em-card">
                <h2>Notifications</h2>
                <div className="em-table">
                  <table>
                    <thead>
                      <tr>
                        <th>Notification</th>
                        <th>Status</th>
                        <th>Created</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(dashboard.messages || []).map((m: Json) => (
                        <tr key={m.id}>
                          <td>{m.subject}</td>
                          <td>{m.state}</td>
                          <td>{new Date(m.created_at).toLocaleString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </>
          )}
          {tab === "Participants" && (
            <section className="em-card">
              <h2>Participants & leads</h2>
              <div className="em-filters">
                <input
                  aria-label="Search participants"
                  placeholder="Name, email, phone, company or code"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
                <select
                  aria-label="Business filter"
                  value={business}
                  onChange={(e) => setBusiness(e.target.value)}
                >
                  {["ALL", "ENERGY", "MEDIA", "SERVICES"].map((v) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
                <select
                  aria-label="Game filter"
                  value={game}
                  onChange={(e) => setGame(e.target.value)}
                >
                  {["ALL", "SPIN", "MATCH", "SCRATCH", "TRIVIA"].map((v) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
                <select
                  aria-label="Marketing consent filter"
                  value={consent}
                  onChange={(e) => setConsent(e.target.value)}
                >
                  <option value="ALL">All consent states</option>
                  <option value="true">Marketing consented</option>
                  <option value="false">No marketing consent</option>
                </select>
                <select
                  aria-label="Conversion filter"
                  value={conversion}
                  onChange={(e) => setConversion(e.target.value)}
                >
                  <option value="ALL">All conversions</option>
                  <option value="true">Paying customers</option>
                  <option value="false">Unconverted leads</option>
                </select>
                <button
                  disabled={busy || !selected}
                  onClick={() =>
                    run(async () =>
                      saveBlob(
                        await apiDownload(
                          `${base}/events/${selected}/export.csv`,
                          { contentType: "text/csv" },
                        ),
                        "campaign-leads.csv",
                      ),
                    )
                  }
                >
                  Export all (Excel CSV)
                </button>
                <button
                  disabled={busy || !selected}
                  onClick={() =>
                    run(async () =>
                      saveBlob(
                        await apiDownload(
                          `${base}/events/${selected}/export.csv`,
                          {
                            contentType: "text/csv",
                            params: { marketingOnly: true },
                          },
                        ),
                        "consented-marketing-leads.csv",
                      ),
                    )
                  }
                >
                  Export consented leads
                </button>
              </div>
              <p>
                {filtered.length} matching participants. Exports include all
                event records or only consented leads.
              </p>
              <div className="em-table">
                <table>
                  <thead>
                    <tr>
                      {[
                        "Participant",
                        "Business",
                        "Consent",
                        "Game / performance",
                        "Reward / voucher",
                        "Conversion",
                      ].map((v) => (
                        <th key={v}>{v}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {filtered.map((p) => (
                      <tr key={p.id}>
                        <td>
                          <strong>{p.full_name}</strong>
                          <br />
                          {p.email}
                          <br />
                          {p.phone}
                          <br />
                          {p.company} {p.industry}
                          <br />
                          <small>
                            {new Date(p.registered_at).toLocaleString()}
                          </small>
                        </td>
                        <td>{p.interest}</td>
                        <td>
                          {p.marketing_consent ? "Opted in" : "No marketing"}
                        </td>
                        <td>
                          {p.game || "Not played"}
                          <br />
                          {p.game_status}
                          <br />
                          {p.tier} {p.moves && `${p.moves} moves`}{" "}
                          {p.matched_pairs != null &&
                            `${p.matched_pairs} pairs`}{" "}
                          {p.score && `${p.score} score`}
                        </td>
                        <td>
                          {p.reward || "—"}
                          <br />
                          {p.code}
                          <br />
                          {p.redeemed_at
                            ? "Redeemed"
                            : p.expires_at
                              ? Date.parse(p.expires_at) < Date.now()
                                ? "Expired"
                                : "Unredeemed"
                              : ""}
                        </td>
                        <td>
                          {p.converted
                            ? `Paying customer · ${p.transaction_ref}`
                            : "Lead"}
                          {access.manage && (
                            <form
                              onSubmit={(e) => {
                                e.preventDefault();
                                const data = new FormData(e.currentTarget);
                                run(async () => {
                                  await apiRequest(
                                    `${base}/participants/${p.id}/conversion`,
                                    {
                                      method: "PUT",
                                      body: {
                                        converted: !p.converted,
                                        reference: data.get("reference") || "",
                                      },
                                    },
                                  );
                                  await refresh();
                                });
                              }}
                            >
                              <input
                                name="reference"
                                aria-label={`Transaction for ${p.full_name}`}
                                placeholder="Transaction reference"
                                required={!p.converted}
                                maxLength={180}
                              />
                              <button disabled={busy}>
                                {p.converted
                                  ? "Reopen lead"
                                  : "Record paying customer"}
                              </button>
                            </form>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
          {tab === "Prizes" && (
            <>
              <section className="em-card">
                <h2>Approved reward inventory</h2>
                <p>
                  Deactivate the event before changing inventory. Unapproved
                  prizes cannot be awarded. Issued reward details remain
                  immutable.
                </p>
                <div className="em-table">
                  <table>
                    <thead>
                      <tr>
                        <th>Reward</th>
                        <th>Category / value</th>
                        <th>Inventory</th>
                        <th>Approval</th>
                        <th>Manage</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(dashboard?.prizes || []).map((p: Json) => (
                        <tr key={p.id}>
                          <td>
                            {p.business}
                            <br />
                            <strong>{p.name}</strong>
                            <br />
                            <small>{p.terms}</small>
                          </td>
                          <td>
                            {p.category}
                            <br />
                            {p.value_ngn
                              ? `NGN ${Number(p.value_ngn).toLocaleString()}`
                              : "Non-monetary"}
                          </td>
                          <td>
                            {p.remaining} left / {p.quantity}
                            <br />
                            {p.issued} issued
                          </td>
                          <td>
                            {p.approved ? "Approved" : "Awaiting approval"}
                            <br />
                            {p.active ? "Active" : "Inactive"}
                          </td>
                          <td>
                            {access.manage && (
                              <button onClick={() => setPrize({ ...p })}>
                                Edit
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
              {access.manage && (
                <form
                  className="em-card em-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    run(async () => {
                      await apiRequest(
                        `${base}/events/${selected}/prizes${prize.id ? `/${prize.id}` : ""}`,
                        { method: prize.id ? "PUT" : "POST", body: prize },
                      );
                      setPrize({ ...emptyPrize });
                      await refresh();
                      setNotice("Prize saved and audited.");
                    });
                  }}
                >
                  <h2>{prize.id ? "Edit prize" : "Add prize"}</h2>
                  <Field label="Business">
                    <select
                      value={prize.business}
                      onChange={(e) =>
                        setPrize({ ...prize, business: e.target.value })
                      }
                    >
                      {["ENERGY", "MEDIA", "SERVICES", "GENERAL"].map((v) => (
                        <option key={v}>{v}</option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Reward name (actual customer-facing label)">
                    <input
                      required
                      maxLength={180}
                      value={prize.name}
                      onChange={(e) =>
                        setPrize({ ...prize, name: e.target.value })
                      }
                    />
                  </Field>
                  <div className="em-columns">
                    <Field label="Category (exactly one)">
                      <select
                        value={prize.category}
                        onChange={(e) =>
                          setPrize({ ...prize, category: e.target.value })
                        }
                      >
                        {Object.keys(blankConfig.weights).map((v) => (
                          <option key={v}>{v}</option>
                        ))}
                      </select>
                    </Field>
                    <Field label="Fixed NGN value (blank for non-monetary)">
                      <input
                        type="number"
                        min="1"
                        max="100000000"
                        value={prize.value_ngn ?? ""}
                        onChange={(e) =>
                          setPrize({
                            ...prize,
                            value_ngn: e.target.value
                              ? Number(e.target.value)
                              : null,
                          })
                        }
                      />
                    </Field>
                    <Field label="Total quantity">
                      <input
                        required
                        type="number"
                        min={prize.issued || 0}
                        max="100000"
                        value={prize.quantity}
                        onChange={(e) =>
                          setPrize({
                            ...prize,
                            quantity: Number(e.target.value),
                          })
                        }
                      />
                    </Field>
                  </div>
                  <Field label="Eligibility & redemption conditions">
                    <textarea
                      required
                      value={prize.terms}
                      maxLength={3000}
                      onChange={(e) =>
                        setPrize({ ...prize, terms: e.target.value })
                      }
                    />
                  </Field>
                  <label>
                    <input
                      type="checkbox"
                      checked={prize.approved}
                      onChange={(e) =>
                        setPrize({ ...prize, approved: e.target.checked })
                      }
                    />{" "}
                    Management approved
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      checked={prize.active}
                      onChange={(e) =>
                        setPrize({ ...prize, active: e.target.checked })
                      }
                    />{" "}
                    Active prize
                  </label>
                  <div className="em-actions">
                    <button disabled={busy || !selected || event?.active}>
                      Save prize
                    </button>
                    <button
                      type="button"
                      onClick={() => setPrize({ ...emptyPrize })}
                    >
                      New prize
                    </button>
                  </div>
                </form>
              )}
            </>
          )}
          {tab === "Redemption" && (
            <section className="em-card">
              <h2>Verify a voucher</h2>
              <form
                className="em-actions"
                onSubmit={(e) => {
                  e.preventDefault();
                  run(async () =>
                    setVoucher(
                      await apiRequest<Json>(
                        `${base}/voucher/${encodeURIComponent(voucherCode.trim())}`,
                      ),
                    ),
                  );
                }}
              >
                <input
                  aria-label="Voucher code"
                  placeholder="ADESA-…"
                  required
                  value={voucherCode}
                  onChange={(e) => setVoucherCode(e.target.value)}
                />
                <button disabled={busy}>Verify</button>
              </form>
              {voucher && (
                <>
                  <h3>{voucher.reward.name}</h3>
                  <p>
                    {voucher.full_name} · {voucher.email} · {voucher.phone}
                  </p>
                  <p>
                    {voucher.reward.business} ·{" "}
                    {voucher.reward.value_ngn
                      ? `NGN ${voucher.reward.value_ngn}`
                      : "Non-monetary"}{" "}
                    · Expires {new Date(voucher.expires_at).toLocaleString()}
                  </p>
                  <p>{voucher.reward.terms}</p>
                  <strong>
                    {voucher.valid
                      ? "Valid for one redemption"
                      : "Redeemed or expired"}
                  </strong>
                  {access.redeem && voucher.valid && (
                    <form
                      className="em-form"
                      onSubmit={(e) => {
                        e.preventDefault();
                        run(async () => {
                          setVoucher(
                            await apiRequest<Json>(
                              `${base}/voucher/${encodeURIComponent(voucher.code)}/redeem`,
                              {
                                method: "POST",
                                body: {
                                  business: redeemBusiness,
                                  transaction: reference,
                                },
                              },
                            ),
                          );
                          await refresh();
                          setNotice(
                            "Redemption recorded with your staff identity and time.",
                          );
                        });
                      }}
                    >
                      <Field label="Applicable subsidiary">
                        <select
                          value={redeemBusiness}
                          onChange={(e) => setRedeemBusiness(e.target.value)}
                        >
                          {["ENERGY", "MEDIA", "SERVICES", "GENERAL"].map(
                            (v) => (
                              <option key={v}>{v}</option>
                            ),
                          )}
                        </select>
                      </Field>
                      <Field label="Customer transaction / gift collection reference">
                        <input
                          required
                          maxLength={180}
                          value={reference}
                          onChange={(e) => setReference(e.target.value)}
                        />
                      </Field>
                      <button disabled={busy}>
                        Confirm single-use redemption
                      </button>
                    </form>
                  )}
                </>
              )}
            </section>
          )}
          {tab === "Events" && access.manage && (
            <EventEditor
              draft={eventDraft}
              onChange={setEventDraft}
              selectedEvent={event}
              prizes={dashboard?.prizes || []}
              busy={busy}
              onNew={() => setEventDraft(initialEvent())}
              onLoad={() => event && editEvent(event)}
              onSave={() =>
                void run(async () => {
                  const body = { ...eventDraft, active: false };
                  const result = await apiRequest<Json | undefined>(
                    `${base}/events${eventDraft.id ? `/${eventDraft.id}` : ""}`,
                    { method: eventDraft.id ? "PUT" : "POST", body },
                  );
                  const id = result?.id || eventDraft.id;
                  await refresh(id, true);
                  setNotice(
                    "Event settings saved. Activate the event above when its rules and prizes are ready.",
                  );
                })
              }
              onToggle={() =>
                void run(async () => {
                  if (!event) return;
                  const active = !event.active;
                  await apiRequest(`${base}/events/${event.id}`, {
                    method: "PUT",
                    body: { ...event, active },
                  });
                  await refresh(event.id, true);
                  setNotice(
                    active
                      ? "Event activated. Registration follows the start and end dates shown above."
                      : "Event deactivated. You can now edit its settings and prizes.",
                  );
                })
              }
            />
          )}
          {tab === "Messages" && (
            <section className="em-card">
              <h2>Notification & email outbox</h2>
              <p>
                PREVIEW means not sent. REVIEW means delivery was attempted and
                requires provider reconciliation before retry. Only isolated
                demonstration events expose email bodies here.
              </p>
              {messages.map((m) => (
                <details key={m.id}>
                  <summary>
                    {m.kind} · {m.subject} · {m.state}
                  </summary>
                  <p>{m.recipient}</p>
                  <pre className="em-message">{m.body}</pre>
                </details>
              ))}
            </section>
          )}
          {tab === "Audit" && (
            <section className="em-card">
              <h2>Campaign audit history</h2>
              {audit.map((a) => (
                <details key={a.id}>
                  <summary>
                    {new Date(a.created_at).toLocaleString()} · {a.actor} ·{" "}
                    {a.action}
                  </summary>
                  <pre className="em-message">
                    {typeof a.detail === "string"
                      ? a.detail
                      : JSON.stringify(a.detail, null, 2)}
                  </pre>
                </details>
              ))}
            </section>
          )}
          {tab === "Permissions" && access.manage && (
            <section className="em-card">
              <h2>Staff access</h2>
              <p>
                Only an existing HQ–Home administrator can grant these
                permissions. VIEW can review and export leads; REDEEM can
                additionally redeem vouchers; MANAGE can configure events,
                prizes and customer conversions. Existing operational
                permissions are unaffected.
              </p>
              <form
                className="em-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  run(async () => {
                    await apiRequest(`${base}/staff`, {
                      method: "PUT",
                      body: { email: staffEmail, capability },
                    });
                    setNotice("Campaign permission saved.");
                  });
                }}
              >
                <Field label="Existing staff email">
                  <input
                    required
                    type="email"
                    value={staffEmail}
                    onChange={(e) => setStaffEmail(e.target.value)}
                  />
                </Field>
                <Field label="Campaign permission">
                  <select
                    value={capability}
                    onChange={(e) => setCapability(e.target.value)}
                  >
                    {["VIEW", "REDEEM", "MANAGE", "NONE"].map((v) => (
                      <option key={v}>{v}</option>
                    ))}
                  </select>
                </Field>
                <button disabled={busy}>Update permission</button>
              </form>
            </section>
          )}
        </>
      )}
    </div>
  );
}
