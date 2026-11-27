import { useEffect, useState } from "react";
import { apiRequest, apiDownload } from "../../api/client";
import { useAuth } from "../../auth/AuthProvider";
import "./vendor-admin.css";
type Row = Record<string, any>;
const base = "/api/v1/vendor/admin";
const statuses = [
  "PENDING_REVIEW",
  "UNDER_REVIEW",
  "ADDITIONAL_INFORMATION_REQUIRED",
  "APPROVED",
  "REJECTED",
  "SUSPENDED",
  "ARCHIVED",
];
const names: Record<string, string> = {
  businessName: "Registered business name",
  businessType: "Business type",
  businessTypeOther: "Other business type",
  address: "Business address",
  cac: "CAC registration number",
  tin: "Tax Identification Number",
  website: "Website",
  contactName: "Primary contact full name",
  contactPhone: "Primary contact phone number",
  contactEmail: "Primary contact email address",
  contactPosition: "Primary contact position",
  categories: "Vendor categories",
  categoryOther: "Other vendor category",
  description: "Description of products or services",
  coverage: "Coverage area",
  paymentTerms: "Payment terms offered",
  paymentOther: "Other payment terms",
  leadDays: "Typical lead time in days",
  warranty: "Warranty or return policy",
  bankName: "Bank name",
  accountName: "Account name",
  accountNumber: "Account number",
  bankFormat: "Account format",
  bankFormatDetails: "Alternative account format details",
  conflict: "Conflict of interest declared",
  conflictDetails: "Conflict of interest details",
  signatoryName: "Authorised signatory",
  signatoryPosition: "Signatory position",
  declarationDate: "Declaration date",
  signatureMethod: "Signature method",
  signatureStyle: "Signature style",
};
const groups = [
  [
    "Business and contact",
    [
      "businessName",
      "businessType",
      "businessTypeOther",
      "address",
      "cac",
      "tin",
      "website",
      "contactName",
      "contactPhone",
      "contactEmail",
      "contactPosition",
    ],
  ],
  [
    "Goods and services",
    [
      "categories",
      "categoryOther",
      "description",
      "coverage",
      "paymentTerms",
      "paymentOther",
      "leadDays",
      "warranty",
    ],
  ],
  [
    "Banking and compliance",
    [
      "bankName",
      "accountName",
      "accountNumber",
      "bankFormat",
      "bankFormatDetails",
      "conflict",
      "conflictDetails",
    ],
  ],
  [
    "Declaration and signature",
    [
      "signatoryName",
      "signatoryPosition",
      "declarationDate",
      "signatureMethod",
      "signatureStyle",
    ],
  ],
] as const;
const label = (text: string) =>
  text
    ?.toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
function save(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function SignatoryImage({ document }: { document: Row }) {
  const [url, setUrl] = useState(""),
    [error, setError] = useState("");
  useEffect(() => {
    let objectUrl = "";
    let active = true;
    async function load() {
      try {
        const ticket = await apiRequest<Row>(
          `${base}/documents/${document.id}/ticket`,
          { method: "POST" },
        );
        const blob = await apiDownload(
          `${base}/documents/download/${ticket.ticket}`,
          { contentType: document.mime_type },
        );
        objectUrl = URL.createObjectURL(blob);
        if (active) setUrl(objectUrl);
        else URL.revokeObjectURL(objectUrl);
      } catch {
        if (active) setError("Signature preview could not be loaded.");
      }
    }
    void load();
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [document.id, document.mime_type]);
  return error ? (
    <p role="alert">{error}</p>
  ) : url ? (
    <img
      src={url}
      className="va-signature"
      alt="Vendor-authorised electronic signature"
    />
  ) : (
    <p>Loading private signature…</p>
  );
}
export function VendorPage() {
  const { user } = useAuth();
  const [overview, setOverview] = useState<Row | null>(null),
    [links, setLinks] = useState<Row[]>([]),
    [profile, setProfile] = useState<Row | null>(null),
    [notices, setNotices] = useState<Row[]>([]),
    [tab, setTab] = useState("Overview"),
    [section, setSection] = useState(0),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [search, setSearch] = useState(""),
    [subsidiary, setSubsidiary] = useState("ALL"),
    [category, setCategory] = useState("ALL"),
    [state, setState] = useState("ALL"),
    [bank, setBank] = useState("ALL"),
    [from, setFrom] = useState(""),
    [to, setTo] = useState(""),
    [linkDraft, setLinkDraft] = useState({
      label: "",
      subsidiary: "HOLDINGS",
      kind: "REUSABLE",
      email: "",
      expiresAt: "",
    }),
    [createdLink, setCreatedLink] = useState(""),
    [note, setNote] = useState(""),
    [nextStatus, setNextStatus] = useState("UNDER_REVIEW"),
    [reason, setReason] = useState(""),
    [bankStatus, setBankStatus] = useState("UNVERIFIED"),
    [bankReason, setBankReason] = useState(""),
    [requestText, setRequestText] = useState(""),
    [requestDays, setRequestDays] = useState(14),
    [requestLink, setRequestLink] = useState(""),
    [related, setRelated] = useState("");
  async function refresh() {
    const [o, l, n] = await Promise.all([
      apiRequest<Row>(`${base}/overview`),
      apiRequest<Row[]>(`${base}/links`),
      apiRequest<Row[]>(`${base}/notices`),
    ]);
    setOverview(o);
    setLinks(l);
    setNotices(n);
  }
  useEffect(() => {
    if (user?.role !== "ADMIN") return;
    void refresh().catch((e) => setError(e.message));
    const id = new URLSearchParams(window.location.search).get("application");
    if (id && /^[0-9a-f-]{36}$/.test(id)) void openProfile(id);
  }, [user?.role]);
  async function work(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await action();
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Unable to complete the request.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function openProfile(id: string, revision = 0) {
    const p = await apiRequest<Row>(
      `${base}/applications/${id}?revision=${revision}`,
    );
    setProfile(p);
    setNextStatus(p.status);
    setBankStatus(
      p.banking_status === "NOT_PROVIDED" ? "UNVERIFIED" : p.banking_status,
    );
    setTab("Profile");
    setSection(0);
    setRequestLink("");
  }
  async function reloadProfile() {
    if (profile) await openProfile(profile.id, profile.revision);
    await refresh();
  }
  const applications: Row[] = overview?.applications || [];
  const filtered = applications.filter(
    (a) =>
      (subsidiary === "ALL" || a.subsidiary === subsidiary) &&
      (state === "ALL" || a.status === state) &&
      (bank === "ALL" || a.banking_status === bank) &&
      (category === "ALL" || a.categories.includes(category)) &&
      (!from || Date.parse(a.submitted_at) >= Date.parse(from)) &&
      (!to || Date.parse(a.submitted_at) < Date.parse(to) + 86400000) &&
      `${a.reference} ${a.businessName} ${a.contactName} ${a.contactEmail} ${a.contactPhone}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  async function documentDownload(d: Row) {
    await work(async () => {
      const ticket = await apiRequest<Row>(`${base}/documents/${d.id}/ticket`, {
        method: "POST",
      });
      save(
        await apiDownload(`${base}/documents/download/${ticket.ticket}`, {
          contentType: d.mime_type,
        }),
        d.filename,
      );
    });
  }
  async function pdf(original = false) {
    if (!profile) return;
    await work(async () => {
      save(
        await apiDownload(`${base}/applications/${profile.id}/pdf`, {
          contentType: "application/pdf",
          params: { revision: original ? 1 : profile.revision },
        }),
        `Vendor_Application_${profile.answers.businessName.replace(/[^A-Za-z0-9_-]/g, "_")}_${profile.reference}.pdf`,
      );
    });
  }
  if (user?.role !== "ADMIN")
    return (
      <p role="alert">
        Vendor Management is restricted to the System Administrator.
      </p>
    );
  return (
    <main className="va-root">
      <header className="va-heading">
        <div>
          <span className="va-eyebrow">
            ADESA HQ – HOME · ADMINISTRATOR ONLY
          </span>
          <h1>Vendor Management</h1>
          <p>Acquire, verify and manage trusted business partners.</p>
        </div>
        <button disabled={busy} onClick={() => work(refresh)}>
          Refresh
        </button>
      </header>
      {overview?.demo && (
        <p className="va-demo">
          LOCAL DEMONSTRATION · Fictional vendors, banking details and
          signatures. Email delivery is disabled.
        </p>
      )}
      {error && (
        <p role="alert" className="va-error">
          {error}
        </p>
      )}
      {message && (
        <p role="status" className="va-message">
          {message}
        </p>
      )}
      <nav className="va-tabs" aria-label="Vendor Management sections">
        {[
          "Overview",
          "Applications",
          "Registration Links",
          "Notifications",
          ...(profile ? ["Profile"] : []),
        ].map((t) => (
          <button
            key={t}
            aria-current={tab === t ? "page" : undefined}
            onClick={() => setTab(t)}
          >
            {t}
            {t === "Notifications" && notices.filter((n) => !n.read_at).length
              ? ` (${notices.filter((n) => !n.read_at).length})`
              : ""}
          </button>
        ))}
      </nav>
      {tab === "Overview" && (
        <>
          <div className="va-stats">
            {[
              ["Total applications", overview?.total || 0],
              ...statuses
                .filter((s) => s !== "SUSPENDED" && s !== "ARCHIVED")
                .map((s) => [label(s), overview?.statuses?.[s] || 0]),
              ["Verified banking", overview?.verifiedBanking || 0],
            ].map(([title, count]) => (
              <section key={title}>
                <span>{title}</span>
                <strong>{count}</strong>
              </section>
            ))}
          </div>
          <div className="va-columns">
            <section className="va-card">
              <h2>Vendors by subsidiary</h2>
              {overview?.subsidiaries?.map((s: Row) => (
                <p className="va-count-row" key={s.code}>
                  <span>{s.name}</span>
                  <strong>
                    {applications.filter((a) => a.subsidiary === s.code).length}
                  </strong>
                </p>
              ))}
            </section>
            <section className="va-card">
              <h2>Vendors by category</h2>
              {Array.from(
                new Set(applications.flatMap((a) => a.categories)),
              ).map((c) => (
                <p className="va-count-row" key={c}>
                  <span>{c}</span>
                  <strong>
                    {
                      applications.filter((a) => a.categories.includes(c))
                        .length
                    }
                  </strong>
                </p>
              ))}
            </section>
          </div>
          <section className="va-card">
            <h2>Recent applications</h2>
            {applications.slice(0, 8).map((a) => (
              <button
                className="va-recent"
                key={a.id}
                onClick={() => work(() => openProfile(a.id))}
              >
                <span>
                  <strong>{a.businessName}</strong>
                  <small>
                    {a.reference} · ADESA {label(a.subsidiary)}
                  </small>
                </span>
                <span className="va-badge">{label(a.status)}</span>
              </button>
            ))}
            {!applications.length && (
              <p>
                No vendor applications yet. Generate a registration link to get
                started.
              </p>
            )}
          </section>
        </>
      )}
      {tab === "Applications" && (
        <section className="va-card">
          <h2>Vendor applications</h2>
          <div className="va-filters">
            <input
              aria-label="Search vendor applications"
              placeholder="Search business, reference, contact…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <select
              aria-label="Filter subsidiary"
              value={subsidiary}
              onChange={(e) => setSubsidiary(e.target.value)}
            >
              <option value="ALL">All subsidiaries</option>
              {overview?.subsidiaries?.map((s: Row) => (
                <option key={s.code} value={s.code}>
                  {s.name}
                </option>
              ))}
            </select>
            <select
              aria-label="Filter category"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
            >
              <option value="ALL">All categories</option>
              {Array.from(
                new Set(applications.flatMap((a) => a.categories)),
              ).map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
            <select
              aria-label="Filter status"
              value={state}
              onChange={(e) => setState(e.target.value)}
            >
              <option value="ALL">All statuses</option>
              {statuses.map((s) => (
                <option key={s} value={s}>
                  {label(s)}
                </option>
              ))}
            </select>
            <select
              aria-label="Filter banking verification"
              value={bank}
              onChange={(e) => setBank(e.target.value)}
            >
              <option value="ALL">All banking states</option>
              {["NOT_PROVIDED", "UNVERIFIED", "VERIFIED", "REJECTED"].map(
                (s) => (
                  <option value={s} key={s}>
                    {label(s)}
                  </option>
                ),
              )}
            </select>
            <label>
              From
              <input
                type="date"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
              />
            </label>
            <label>
              To
              <input
                type="date"
                value={to}
                onChange={(e) => setTo(e.target.value)}
              />
            </label>
          </div>
          <div className="va-table">
            <table>
              <thead>
                <tr>
                  {[
                    "Reference / business",
                    "Subsidiary / categories",
                    "Contact",
                    "Submitted",
                    "Status / banking",
                    "Actions",
                  ].map((t) => (
                    <th key={t}>{t}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map((a) => (
                  <tr key={a.id}>
                    <td>
                      <strong>{a.businessName}</strong>
                      <br />
                      {a.reference}
                      {a.possibleDuplicates > 0 && (
                        <p className="va-flag">
                          Possible duplicates for review
                        </p>
                      )}
                      {a.conflict && (
                        <p className="va-flag">Interest declared — review</p>
                      )}
                    </td>
                    <td>
                      {label(a.subsidiary)}
                      <br />
                      <small>{a.categories.join(", ")}</small>
                    </td>
                    <td>
                      {a.contactName}
                      <br />
                      {a.contactEmail}
                      <br />
                      {a.contactPhone}
                    </td>
                    <td>{new Date(a.submitted_at).toLocaleString()}</td>
                    <td>
                      {label(a.status)}
                      <br />
                      {label(a.banking_status)}
                      <br />
                      {a.eligibleForPayment
                        ? "Eligible for payment"
                        : "Not eligible for payment"}
                    </td>
                    <td>
                      <button onClick={() => work(() => openProfile(a.id))}>
                        View application
                      </button>
                      <button
                        className="va-light"
                        disabled={busy}
                        onClick={() =>
                          work(async () =>
                            save(
                              await apiDownload(
                                `${base}/applications/${a.id}/pdf`,
                                { contentType: "application/pdf" },
                              ),
                              `Vendor_Application_${a.reference}.pdf`,
                            ),
                          )
                        }
                      >
                        Export application as PDF
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {tab === "Registration Links" && (
        <>
          <section className="va-card">
            <h2>Create a registration link</h2>
            <p>
              Links identify the subsidiary automatically. Invitation tokens are
              unguessable and can be revoked. Do not place these pages in public
              navigation.
            </p>
            <form
              className="va-form"
              onSubmit={(e) => {
                e.preventDefault();
                work(async () => {
                  const result = await apiRequest<Row>(`${base}/links`, {
                    method: "POST",
                    body: {
                      ...linkDraft,
                      expiresAt: linkDraft.expiresAt
                        ? new Date(linkDraft.expiresAt).toISOString()
                        : null,
                    },
                  });
                  setCreatedLink(result.url);
                  await refresh();
                  setMessage("Secure registration link generated.");
                });
              }}
            >
              <label>
                Link label
                <input
                  required
                  maxLength={160}
                  value={linkDraft.label}
                  onChange={(e) =>
                    setLinkDraft({ ...linkDraft, label: e.target.value })
                  }
                />
              </label>
              <div className="va-columns">
                <label>
                  Subsidiary
                  <select
                    value={linkDraft.subsidiary}
                    onChange={(e) =>
                      setLinkDraft({ ...linkDraft, subsidiary: e.target.value })
                    }
                  >
                    {overview?.subsidiaries?.map((s: Row) => (
                      <option value={s.code} key={s.code}>
                        {s.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Link type
                  <select
                    value={linkDraft.kind}
                    onChange={(e) =>
                      setLinkDraft({ ...linkDraft, kind: e.target.value })
                    }
                  >
                    <option value="REUSABLE">Reusable subsidiary link</option>
                    <option value="INVITATION">
                      Individual invitation — one submission
                    </option>
                  </select>
                </label>
              </div>
              <div className="va-columns">
                <label>
                  Invited email (optional)
                  <input
                    type="email"
                    value={linkDraft.email}
                    onChange={(e) =>
                      setLinkDraft({ ...linkDraft, email: e.target.value })
                    }
                  />
                </label>
                <label>
                  Expiry date and time (optional)
                  <input
                    type="datetime-local"
                    value={linkDraft.expiresAt}
                    onChange={(e) =>
                      setLinkDraft({ ...linkDraft, expiresAt: e.target.value })
                    }
                  />
                </label>
              </div>
              <button disabled={busy}>Generate registration link</button>
            </form>
            {createdLink && (
              <div className="va-link-result">
                <a href={createdLink} target="_blank" rel="noreferrer">
                  Preview registration form ↗
                </a>
                <button
                  onClick={() =>
                    work(async () => {
                      await navigator.clipboard.writeText(createdLink);
                      setMessage("Registration link copied.");
                    })
                  }
                >
                  Copy new link
                </button>
              </div>
            )}
          </section>
          <section className="va-card">
            <h2>Registration links</h2>
            <div className="va-table">
              <table>
                <thead>
                  <tr>
                    <th>Label / subsidiary</th>
                    <th>Type</th>
                    <th>Status / expiry</th>
                    <th>Applications</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {links.map((l) => (
                    <tr key={l.id}>
                      <td>
                        {l.label}
                        <br />
                        {label(l.subsidiary)}
                      </td>
                      <td>{label(l.kind)}</td>
                      <td>
                        {l.revoked_at
                          ? "Revoked"
                          : !l.active
                            ? "Inactive"
                            : l.expires_at &&
                                Date.parse(l.expires_at) < Date.now()
                              ? "Expired"
                              : "Active"}
                        <br />
                        {l.expires_at
                          ? new Date(l.expires_at).toLocaleString()
                          : "No expiry"}
                      </td>
                      <td>{l.uses}</td>
                      <td>
                        <button
                          disabled={Boolean(l.revoked_at)}
                          onClick={() =>
                            work(async () => {
                              await navigator.clipboard.writeText(l.url);
                              setMessage("Registration link copied.");
                            })
                          }
                        >
                          Copy link
                        </button>
                        <a href={l.url} target="_blank" rel="noreferrer">
                          Preview ↗
                        </a>
                        {!l.revoked_at && (
                          <>
                            <button
                              className="va-light"
                              disabled={busy}
                              onClick={() =>
                                work(async () => {
                                  await apiRequest(`${base}/links/${l.id}`, {
                                    method: "PUT",
                                    body: {
                                      state: l.active ? "INACTIVE" : "ACTIVE",
                                    },
                                  });
                                  await refresh();
                                })
                              }
                            >
                              {l.active ? "Deactivate" : "Activate"}
                            </button>
                            <button
                              className="va-danger"
                              disabled={busy}
                              onClick={() =>
                                work(async () => {
                                  await apiRequest(`${base}/links/${l.id}`, {
                                    method: "PUT",
                                    body: { state: "REVOKED" },
                                  });
                                  await refresh();
                                })
                              }
                            >
                              Revoke permanently
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
      {tab === "Notifications" && (
        <section className="va-card">
          <h2>Administrator notifications</h2>
          <p>
            These notices are restricted to current System Administrators.
            Sensitive banking, signature and document content is not included in
            emails.
          </p>
          {notices.map((n) => (
            <div className="va-notice" key={n.id}>
              <div>
                <strong>{label(n.kind)}</strong>
                <p>
                  {n.reference} · {new Date(n.created_at).toLocaleString()}
                </p>
              </div>
              <button
                onClick={() =>
                  work(async () => {
                    await apiRequest(`${base}/notices/${n.id}/read`, {
                      method: "POST",
                    });
                    await openProfile(n.application_id);
                    await refresh();
                  })
                }
              >
                View application
              </button>
            </div>
          ))}
          {!notices.length && <p>No notifications yet.</p>}
        </section>
      )}
      {tab === "Profile" && profile && (
        <>
          <section className="va-card">
            <div className="va-heading">
              <div>
                <span className="va-eyebrow">
                  {profile.reference} · {label(profile.subsidiary)}
                </span>
                <h2>{profile.answers.businessName}</h2>
                <p>
                  Current status: <strong>{label(profile.status)}</strong> ·{" "}
                  {profile.eligibleForPayment
                    ? "Eligible for payment"
                    : "Not eligible for payment"}
                </p>
              </div>
              <div className="va-actions">
                <button disabled={busy} onClick={() => pdf(true)}>
                  Export original application as PDF
                </button>
                <button
                  className="va-light"
                  disabled={busy}
                  onClick={() => pdf()}
                >
                  Export selected revision
                </button>
              </div>
            </div>
            <label className="va-revision">
              Signed application version
              <select
                value={profile.revision}
                onChange={(e) =>
                  work(() => openProfile(profile.id, Number(e.target.value)))
                }
              >
                {profile.revisions.map((r: Row) => (
                  <option key={r.number} value={r.number}>
                    {r.number === 1
                      ? "Original signed application"
                      : `Signed amendment ${r.number - 1}`}{" "}
                    · {new Date(r.submitted_at).toLocaleString()}
                  </option>
                ))}
              </select>
            </label>
            <nav className="va-tabs" aria-label="Vendor profile sections">
              {groups.map(([name], i) => (
                <button
                  key={name}
                  aria-current={section === i ? "page" : undefined}
                  onClick={() => setSection(i)}
                >
                  {name}
                </button>
              ))}
            </nav>
            <dl className="va-answers">
              {groups[section]?.[1].map((key) => (
                <div key={key}>
                  <dt>{names[key]}</dt>
                  <dd>
                    {Array.isArray(profile.answers[key])
                      ? profile.answers[key].join(", ")
                      : profile.answers[key] || "Not provided"}
                  </dd>
                </div>
              ))}
            </dl>
            {section === 2 && (
              <>
                <p className="va-warning">
                  Bank details will be independently verified before the first
                  payment is made. Approval and banking verification are
                  separate.
                </p>
                <h3>Supporting documents</h3>
                {profile.documents
                  .filter((d: Row) => d.category !== "SIGNATURE")
                  .map((d: Row) => (
                    <div className="va-document" key={d.id}>
                      <span>
                        <strong>{d.filename}</strong>
                        <small>
                          {d.category} ·{" "}
                          {new Date(d.created_at).toLocaleString()} · Validated;
                          private storage
                        </small>
                      </span>
                      <button
                        disabled={busy}
                        onClick={() => documentDownload(d)}
                      >
                        Download privately
                      </button>
                    </div>
                  ))}
                <form
                  className="va-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    work(async () => {
                      await apiRequest(
                        `${base}/applications/${profile.id}/banking`,
                        {
                          method: "PUT",
                          body: { status: bankStatus, reason: bankReason },
                        },
                      );
                      setBankReason("");
                      await reloadProfile();
                      setSection(2);
                      setMessage("Independent banking verification recorded.");
                    });
                  }}
                >
                  <h3>Administrator banking verification</h3>
                  <p>
                    Current verification: {label(profile.banking_status)}
                    {profile.banking_verified_at
                      ? ` · ${new Date(profile.banking_verified_at).toLocaleString()} · ${profile.banking_verified_by}`
                      : ""}
                  </p>
                  <label>
                    Verification outcome
                    <select
                      value={bankStatus}
                      onChange={(e) => setBankStatus(e.target.value)}
                    >
                      {["UNVERIFIED", "VERIFIED", "REJECTED"].map((s) => (
                        <option key={s}>{s}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Independent verification evidence
                    <input
                      required
                      maxLength={3000}
                      value={bankReason}
                      onChange={(e) => setBankReason(e.target.value)}
                    />
                  </label>
                  <button
                    disabled={busy || profile.banking_status === "NOT_PROVIDED"}
                  >
                    Record banking verification
                  </button>
                </form>
              </>
            )}
            {section === 3 && (
              <>
                <h3>Data Protection Notice</h3>
                <p>{profile.answers.privacyWording}</p>
                <h3>Signed declaration</h3>
                <p>{profile.answers.declarationWording}</p>
                <p>
                  Explicitly accepted:{" "}
                  {profile.answers.declarationAccepted ? "Yes" : "No"}
                </p>
                <h3>Electronic signature consent</h3>
                <p>{profile.answers.signatureConsentWording}</p>
                <p>
                  Explicitly accepted:{" "}
                  {profile.answers.signatureConsent ? "Yes" : "No"}
                </p>
                {profile.documents
                  .filter(
                    (d: Row) =>
                      d.category === "SIGNATURE" &&
                      d.id === profile.answers.signatureId,
                  )
                  .map((d: Row) => (
                    <SignatoryImage key={d.id} document={d} />
                  ))}
                <p className="va-small">
                  Typed signatures visually represent the entered name; they are
                  not independently verified handwritten signatures. Signed
                  answers cannot be edited by the administrator.
                </p>
              </>
            )}
          </section>
          <div className="va-columns">
            <section className="va-card">
              <h2>Review & status</h2>
              <form
                className="va-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  work(async () => {
                    await apiRequest(
                      `${base}/applications/${profile.id}/status`,
                      { method: "PUT", body: { status: nextStatus, reason } },
                    );
                    setReason("");
                    await reloadProfile();
                    setMessage("Status change recorded in the audit history.");
                  });
                }}
              >
                <label>
                  Application status
                  <select
                    value={nextStatus}
                    onChange={(e) => setNextStatus(e.target.value)}
                  >
                    {statuses.map((s) => (
                      <option key={s} value={s}>
                        {label(s)}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Reason / vendor-facing message
                  <textarea
                    required={["REJECTED", "SUSPENDED", "ARCHIVED"].includes(
                      nextStatus,
                    )}
                    value={reason}
                    maxLength={5000}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <button disabled={busy}>Update application status</button>
              </form>
              <form
                className="va-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  work(async () => {
                    await apiRequest(
                      `${base}/applications/${profile.id}/notes`,
                      { method: "POST", body: { body: note } },
                    );
                    setNote("");
                    await reloadProfile();
                  });
                }}
              >
                <h3>Private administrator note</h3>
                <label>
                  Verification note
                  <textarea
                    required
                    maxLength={5000}
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                  />
                </label>
                <button disabled={busy}>Save private note</button>
              </form>
              {profile.notes.map((n: Row) => (
                <div className="va-note" key={n.id}>
                  <small>
                    {n.author} · {new Date(n.createdAt).toLocaleString()}
                  </small>
                  <p>{n.body}</p>
                </div>
              ))}
            </section>
            <section className="va-card">
              <h2>Request information or corrections</h2>
              <p>
                Request trade references, director ID, insurance, proof of
                address or material corrections as needed. The vendor receives a
                private link and signs a new revision; the original stays
                intact.
              </p>
              <form
                className="va-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  work(async () => {
                    const result = await apiRequest<Row>(
                      `${base}/applications/${profile.id}/requests`,
                      {
                        method: "POST",
                        body: { message: requestText, days: requestDays },
                      },
                    );
                    await reloadProfile();
                    setRequestLink(result.url);
                    setMessage(
                      "Additional information request queued for the vendor.",
                    );
                  });
                }}
              >
                <label>
                  Requested information
                  <textarea
                    required
                    maxLength={5000}
                    value={requestText}
                    onChange={(e) => setRequestText(e.target.value)}
                  />
                </label>
                <label>
                  Link validity (days)
                  <input
                    type="number"
                    required
                    min={1}
                    max={90}
                    value={requestDays}
                    onChange={(e) => setRequestDays(Number(e.target.value))}
                  />
                </label>
                <button disabled={busy}>Send secure information request</button>
              </form>
              {requestLink && (
                <div className="va-link-result">
                  <a href={requestLink} target="_blank" rel="noreferrer">
                    Preview vendor correction form ↗
                  </a>
                  <button
                    onClick={() =>
                      work(async () => {
                        await navigator.clipboard.writeText(requestLink);
                        setMessage("Request link copied.");
                      })
                    }
                  >
                    Copy request link
                  </button>
                </div>
              )}
              {profile.requests.map((r: Row) => (
                <p className="va-small" key={r.id}>
                  Requested {new Date(r.created_at).toLocaleDateString()} ·{" "}
                  {r.consumed_at
                    ? "Completed"
                    : `Expires ${new Date(r.expires_at).toLocaleDateString()}`}
                </p>
              ))}
              <h3>Possible duplicate vendors</h3>
              <p>
                Matches are for review, not automatic rejection.
                Cross-subsidiary applications remain separate.
              </p>
              {profile.possibleDuplicates.map((a: Row) => (
                <button
                  className="va-light"
                  key={a.id}
                  onClick={() => work(() => openProfile(a.id))}
                >
                  {a.reference} · {label(a.subsidiary)}
                </button>
              ))}
              <form
                className="va-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  work(async () => {
                    await apiRequest(
                      `${base}/applications/${profile.id}/related/${related}`,
                      { method: "PUT" },
                    );
                    await reloadProfile();
                    setMessage(
                      "Applications associated with the same vendor entity.",
                    );
                  });
                }}
              >
                <label>
                  Associate with an existing application
                  <select
                    required
                    value={related}
                    onChange={(e) => setRelated(e.target.value)}
                  >
                    <option value="">Choose a related application</option>
                    {applications
                      .filter((a) => a.id !== profile.id)
                      .map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.reference} · {a.businessName}
                        </option>
                      ))}
                  </select>
                </label>
                <button disabled={busy}>Associate related vendor</button>
              </form>
              {profile.relatedApplications.map((a: Row) => (
                <p key={a.id}>
                  {a.reference} · {label(a.subsidiary)}
                </p>
              ))}
            </section>
          </div>
          <section className="va-card">
            <h2>Application history</h2>
            {profile.history.map((h: Row) => (
              <div className="va-history" key={h.id}>
                <strong>{label(h.action)}</strong>
                <small>
                  {h.actor} · {new Date(h.createdAt).toLocaleString()}
                </small>
                <dl>
                  {Object.entries(h.detail).map(([key, value]) => (
                    <div key={key}>
                      <dt>{label(key)}</dt>
                      <dd>{String(value)}</dd>
                    </div>
                  ))}
                </dl>
              </div>
            ))}
          </section>
        </>
      )}
    </main>
  );
}
