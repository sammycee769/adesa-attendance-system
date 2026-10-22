import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, apiRequest, readCookie } from './client';

/** Runs a request that is expected to fail and returns the resulting error. */
async function captureError(promise: Promise<unknown>): Promise<ApiError> {
  let caught: unknown;
  try {
    await promise;
  } catch (cause) {
    caught = cause;
  }
  expect(caught).toBeInstanceOf(ApiError);
  return caught as ApiError;
}

describe('api client', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });

  it('sends the session cookie and the CSRF token on a state-changing request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await apiRequest('/api/v1/attendance/sign-in', { method: 'POST', body: { deviceProofId: 'p' } });

    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/attendance/sign-in');
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('include');
    expect((options.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('token-123');
    expect((options.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('does not send a CSRF token on a read request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await apiRequest('/api/v1/attendance/me/today');

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((options.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('turns an error response into an ApiError with the server message', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          timestamp: '2026-10-21T10:00:00Z',
          status: 403,
          error: 'Forbidden',
          message: 'Attendance is only available from the approved ADESA office network.',
          path: '/api/v1/attendance/sign-in',
          fieldErrors: [],
        }),
        { status: 403, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const error = await captureError(
      apiRequest('/api/v1/attendance/sign-in', { method: 'POST', body: {} }),
    );

    expect(error.status).toBe(403);
    expect(error.isForbidden).toBe(true);
    expect(error.message).toContain('approved ADESA office network');
  });

  it('reports an unreachable server without leaking technical detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    const error = await captureError(apiRequest('/api/v1/attendance/me/today'));

    expect(error.status).toBe(0);
    expect(error.message).not.toContain('Failed to fetch');
  });

  it('reads a cookie value', () => {
    expect(readCookie('XSRF-TOKEN')).toBe('token-123');
    expect(readCookie('UNKNOWN')).toBeNull();
  });

  it('does not throw on a malformed percent-encoded cookie value', () => {
    document.cookie = 'XSRF-TOKEN=%E0%A4%A';
    expect(readCookie('XSRF-TOKEN')).toBeNull();
  });

  it('times out a request that never settles instead of hanging forever', async () => {
    vi.useFakeTimers();
    try {
      // fetch that resolves only when its signal aborts.
      const fetchMock = vi.fn(
        (_url: string, options: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            options.signal?.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')),
            );
          }),
      );
      vi.stubGlobal('fetch', fetchMock);

      const pending = apiRequest('/api/v1/attendance/me/today', { timeoutMs: 1000 });
      const assertion = captureError(pending);
      await vi.advanceTimersByTimeAsync(1500);

      const error = await assertion;
      expect(error.status).toBe(0);
      expect(error.message).toMatch(/did not respond in time/i);
    } finally {
      vi.useRealTimers();
      vi.unstubAllGlobals();
    }
  });

  it('reports a non-PDF download as an error instead of saving it', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('<html>edge error</html>', {
        status: 200,
        headers: { 'Content-Type': 'text/html' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const { apiDownload } = await import('./client');
    const error = await captureError(apiDownload('/api/v1/admin/attendance/export'));

    expect(error.status).toBe(0);
    expect(error.message).toMatch(/expected format/i);
  });
});
