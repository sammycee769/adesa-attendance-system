import type { ApiErrorBody, FieldViolation } from './types';

/**
 * The single HTTP entry point of the frontend.
 *
 * - The session cookie is always sent (`credentials: 'include'`); the session
 *   is server-side, the browser never holds credentials.
 * - Every state-changing request echoes the CSRF token from the
 *   `XSRF-TOKEN` cookie in the `X-XSRF-TOKEN` header, as the backend
 *   expects.
 * - Errors are normalised into {@link ApiError} so that the UI never has to
 *   deal with raw responses. The backend messages are safe for clients (no
 *   stack traces, no infrastructure detail), so they are shown as-is.
 */

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

const CSRF_COOKIE_NAME = 'XSRF-TOKEN';
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';

/** A failed API call. */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: FieldViolation[];

  constructor(status: number, message: string, fieldErrors: FieldViolation[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }

  /** True when the session is missing or expired. */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  /** True when the request was refused by a policy (network, device, account). */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** True when the request contradicts the current state (already signed in…). */
  get isConflict(): boolean {
    return this.status === 409;
  }
}

export function readCookie(name: string): string | null {
  if (typeof document === 'undefined') {
    return null;
  }
  const match = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${name}=`));
  if (!match) {
    return null;
  }
  try {
    return decodeURIComponent(match.slice(name.length + 1));
  } catch {
    // A malformed percent-encoded value must never escape as a raw URIError.
    return null;
  }
}

let csrfRequest: Promise<void> | null = null;

/**
 * Makes sure the browser holds a CSRF token. The backend writes it to the
 * `XSRF-TOKEN` cookie; the token is only usable together with the session
 * cookie, so it is fetched once and reused.
 */
export async function ensureCsrfToken(): Promise<void> {
  if (readCookie(CSRF_COOKIE_NAME)) {
    return;
  }
  if (!csrfRequest) {
    csrfRequest = fetch(`${API_BASE_URL}/api/v1/auth/csrf`, {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
      .then((response) => {
        if (!response.ok) {
          throw new ApiError(response.status, 'The session could not be initialised.');
        }
      })
      .finally(() => {
        csrfRequest = null;
      });
  }
  await csrfRequest;
}

/** Drops the cached CSRF bootstrap (after a login, the token changes). */
export function resetCsrfBootstrap(): void {
  csrfRequest = null;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
  /** Deadline for the whole call. Defaults to {@link DEFAULT_TIMEOUT_MS}. */
  timeoutMs?: number;
}

/** A stuck proxy or database must not leave the UI waiting forever. */
export const DEFAULT_TIMEOUT_MS = 30_000;

interface Deadline {
  signal: AbortSignal;
  didTimeout: () => boolean;
  cleanup: () => void;
}

/**
 * Combines the caller's signal with a hard deadline.
 *
 * `AbortSignal.any`/`AbortSignal.timeout` are avoided so the helper works in
 * every supported browser and in the test environment; the timer is always
 * cleared by the caller.
 */
function startDeadline(signal: AbortSignal | undefined, timeoutMs: number): Deadline {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const forwardAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) {
      controller.abort();
    } else {
      signal.addEventListener('abort', forwardAbort, { once: true });
    }
  }
  return {
    signal: controller.signal,
    didTimeout: () => timedOut,
    cleanup: () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', forwardAbort);
    },
  };
}

/** Values that can be sent as a query parameter. */
export type QueryValue = string | number | boolean | undefined | null;

/**
 * Builds a query string from a parameter map.
 *
 * Empty values are dropped, so an unused filter never reaches the backend as
 * an empty string.
 */
export function buildQuery(params: Record<string, QueryValue> = {}): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    search.set(key, String(value));
  }
  const query = search.toString();
  return query ? `?${query}` : '';
}

function defaultMessage(status: number): string {
  switch (status) {
    case 400:
      return 'The request was not valid. Please try again.';
    case 401:
      return 'Your session has expired. Please sign in again.';
    case 403:
      return 'This action is not permitted.';
    case 404:
      return 'The requested item was not found.';
    case 409:
      return 'The action conflicts with the current state.';
    case 429:
      return 'Too many attempts. Please wait a moment and try again.';
    default:
      return 'The request could not be completed. Please try again.';
  }
}

async function readErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    const text = await response.text();
    if (!text) {
      return null;
    }
    return JSON.parse(text) as ApiErrorBody;
  } catch {
    return null;
  }
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (method !== 'GET') {
    await ensureCsrfToken();
    const token = readCookie(CSRF_COOKIE_NAME);
    if (token) {
      headers[CSRF_HEADER_NAME] = token;
    }
  }

  let response: Response;
  const deadline = startDeadline(options.signal, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  try {
    try {
      response = await fetch(`${API_BASE_URL}${path}`, {
        method,
        headers,
        credentials: 'include',
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: deadline.signal,
      });
    } catch (cause) {
      if (deadline.didTimeout()) {
        throw new ApiError(0, 'The server did not respond in time. Please try again.');
      }
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        throw cause;
      }
      throw new ApiError(0, 'The server could not be reached. Check your connection and try again.');
    }

    if (!response.ok) {
      const body = await readErrorBody(response);
      throw new ApiError(
        response.status,
        body?.message ?? defaultMessage(response.status),
        body?.fieldErrors ?? [],
      );
    }

    if (response.status === 204) {
      return undefined as T;
    }
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  } finally {
    deadline.cleanup();
  }
}

export interface DownloadOptions {
  params?: Record<string, QueryValue>;
  signal?: AbortSignal;
  /** Deadline for the whole call. Defaults to {@link DEFAULT_TIMEOUT_MS}. */
  timeoutMs?: number;
}

/**
 * Downloads a file from the API (the attendance report).
 *
 * The session rules are the same as for {@link apiRequest}: the request is
 * authenticated by the session cookie, and the answer is a file instead of
 * JSON.
 */
export async function apiDownload(path: string, options: DownloadOptions = {}): Promise<Blob> {
  const url = `${API_BASE_URL}${path}${buildQuery(options.params ?? {})}`;
  const deadline = startDeadline(options.signal, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);

  try {
    let response: Response;
    try {
      response = await fetch(url, {
        method: 'GET',
        headers: { Accept: 'application/pdf' },
        credentials: 'include',
        signal: deadline.signal,
      });
    } catch (cause) {
      if (deadline.didTimeout()) {
        throw new ApiError(0, 'The server did not respond in time. Please try again.');
      }
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        throw cause;
      }
      throw new ApiError(0, 'The server could not be reached. Check your connection and try again.');
    }

    if (!response.ok) {
      const body = await readErrorBody(response);
      throw new ApiError(
        response.status,
        body?.message ?? defaultMessage(response.status),
        body?.fieldErrors ?? [],
      );
    }

    const contentType = response.headers.get('Content-Type') ?? '';
    if (!contentType.includes('application/pdf')) {
      throw new ApiError(0, 'The report could not be downloaded in the expected format.');
    }

    return await response.blob();
  } finally {
    deadline.cleanup();
  }
}
