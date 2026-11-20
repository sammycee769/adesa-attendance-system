import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { signIn, signOut } from './attendance';
import { resetCsrfBootstrap } from './client';

describe('Attendance geolocation', () => {
  const locate = vi.fn();
  const watch = vi.fn();
  const clearWatch = vi.fn();
  const fetch = vi.fn();
  const now = new Date('2026-11-20T09:00:00Z');
  const position = (timestamp = Date.now(), accuracy = 12, latitude = 6.6412393) => ({
    coords: { latitude, longitude: 3.371182, accuracy },
    timestamp,
  });

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(now);
    resetCsrfBootstrap();
    document.cookie = 'XSRF-TOKEN=test-csrf';
    locate.mockReset();
    watch.mockReset();
    clearWatch.mockReset();
    fetch.mockReset().mockImplementation((_url, options) => Promise.resolve(
      options.method === 'POST'
        ? new Response('{"id":"record"}')
        : new Response('{"maxAccuracyMetres":40}'),
    ));
    vi.stubGlobal('fetch', fetch);
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: { getCurrentPosition: locate, watchPosition: watch, clearWatch } });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    Reflect.deleteProperty(navigator, 'geolocation');
  });

  it('uses a new reading for clock-in and clock-out with previously granted permission', async () => {
    // A browser with permission already granted can invoke success without a prompt.
    locate.mockImplementationOnce(success => success(position()));
    await expect(signIn('in-proof')).resolves.toEqual({ id: 'record' });

    vi.setSystemTime(now.getTime() + 1_000);
    locate.mockImplementationOnce(success => success(position(Date.now(), 8, 6.64124)));
    await expect(signOut('out-proof')).resolves.toEqual({ id: 'record' });

    expect(locate).toHaveBeenCalledTimes(2);
    for (const [, , options] of locate.mock.calls) {
      expect(options).toEqual({ enableHighAccuracy: true, maximumAge: 0, timeout: 15000 });
    }
    const posts = fetch.mock.calls.filter(([, options]) => options.method === 'POST');
    expect(posts.map(([url]) => url)).toEqual(['/api/v1/attendance/sign-in', '/api/v1/attendance/sign-out']);
    expect(posts.map(([, options]) => JSON.parse(options.body))).toEqual([
      { deviceProofId: 'in-proof', location: { latitude: 6.6412393, longitude: 3.371182, accuracyMetres: 12, timestampMillis: now.getTime() } },
      { deviceProofId: 'out-proof', location: { latitude: 6.64124, longitude: 3.371182, accuracyMetres: 8, timestampMillis: now.getTime() + 1_000 } },
    ]);
  });

  it('waits for a fresh reading when Safari returns the previous attempt’s coordinates first', async () => {
    const oldFix = position();
    locate.mockImplementation(success => success(oldFix));
    await signIn('in-proof');

    vi.setSystemTime(now.getTime() + 500);
    watch.mockImplementation(success => {
      success(oldFix);
      success(position(Date.now() + 500));
      success(position(Date.now(), 8, 6.64124));
      return 0;
    });
    await expect(signOut('out-proof')).resolves.toEqual({ id: 'record' });

    expect(watch).toHaveBeenCalledWith(expect.any(Function), expect.any(Function),
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 });
    expect(clearWatch).toHaveBeenCalledWith(0);
    const posts = fetch.mock.calls.filter(([, options]) => options.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(JSON.parse(posts[1]?.[1].body).location).toMatchObject({
      latitude: 6.64124, accuracyMetres: 8, timestampMillis: Date.now(),
    });
  });

  it('times out and submits nothing when the browser only returns stale readings', async () => {
    const oldFix = position(Date.now() - 500);
    locate.mockImplementation(success => success(oldFix));
    watch.mockImplementation(success => {
      success(oldFix);
      return 4;
    });
    const failure = expect(signIn('proof')).rejects.toThrow(/timed out/);
    await vi.advanceTimersByTimeAsync(15_000);
    await failure;
    expect(clearWatch).toHaveBeenCalledWith(4);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each([0, 41, Number.NaN])('rejects accuracy of %s against the configured limit', async accuracy => {
    locate.mockImplementation(success => success(position(Date.now(), accuracy)));
    await expect(signIn('proof')).rejects.toThrow(/Precise Location/);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each([Number.NaN, Infinity, 1])('rejects an invalid timestamp offset of %s', async offset => {
    locate.mockImplementation(success => success(position(Date.now() + offset)));
    watch.mockReturnValue(8);
    const failure = expect(signOut('proof')).rejects.toThrow(/invalid timestamp/);
    await vi.advanceTimersByTimeAsync(15_000);
    await failure;
    expect(clearWatch).toHaveBeenCalledWith(8);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each([
    [1, /permission was denied/],
    [2, /Location is unavailable/],
    [3, /timed out/],
  ])('reports browser error %i without submitting attendance', async (code, message) => {
    locate.mockImplementation((_success, error) => error({ code }));
    await expect(signIn('proof')).rejects.toThrow(message);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('surfaces an outside-radius response without retrying', async () => {
    locate.mockImplementation(success => success(position(Date.now(), 8, 6.65)));
    const message = 'Your location is outside the permitted office attendance radius.';
    fetch.mockResolvedValueOnce(new Response('{"maxAccuracyMetres":40}'))
      .mockResolvedValueOnce(new Response(JSON.stringify({ message }), { status: 403 }));
    await expect(signOut('proof')).rejects.toThrow(message);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(locate).toHaveBeenCalledTimes(1);
  });
});
