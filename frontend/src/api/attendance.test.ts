import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { signIn, signOut } from './attendance';
import { resetCsrfBootstrap } from './client';

const locationRequired = () => new Response(JSON.stringify({ code: 'LOCATION_REQUIRED', message: 'Location required' }), { status: 403 });

describe('IP-first attendance with automatic location fallback', () => {
  const locate = vi.fn();
  beforeEach(() => {
    resetCsrfBootstrap();
    document.cookie = 'XSRF-TOKEN=test-csrf';
    locate.mockReset();
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: { getCurrentPosition: locate } });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('does not request location when the IP check succeeds', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{"id":"record"}'));
    vi.stubGlobal('fetch', fetch);
    await expect(signIn('proof')).resolves.toEqual({ id: 'record' });
    expect(locate).not.toHaveBeenCalled();
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each([signIn, signOut])('automatically retries the same action and proof once with a fresh location', async action => {
    const timestamp = Date.now();
    locate.mockImplementation(success => success({ coords: { latitude: 6.6412393, longitude: 3.371182, accuracy: 12 }, timestamp }));
    const fetch = vi.fn().mockResolvedValueOnce(locationRequired()).mockResolvedValueOnce(new Response('{"id":"record"}'));
    vi.stubGlobal('fetch', fetch);
    await action('proof');
    expect(locate).toHaveBeenCalledWith(expect.any(Function), expect.any(Function), { enableHighAccuracy: true, maximumAge: 0, timeout: 20000 });
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls[1]?.[0]).toBe(fetch.mock.calls[0]?.[0]);
    expect(JSON.parse(fetch.mock.calls[1]?.[1].body)).toEqual({ deviceProofId: 'proof', location: { latitude: 6.6412393, longitude: 3.371182, accuracyMetres: 12, timestampMillis: timestamp } });
  });

  it.each([401, 403, 409, 500])('does not request location for an unrelated HTTP %i error', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"Rejected"}', { status })));
    await expect(signIn('proof')).rejects.toMatchObject({ status });
    expect(locate).not.toHaveBeenCalled();
  });

  it('stops when location permission is denied', async () => {
    locate.mockImplementation((_success, error) => error({ code: 1 }));
    const fetch = vi.fn().mockResolvedValueOnce(locationRequired());
    vi.stubGlobal('fetch', fetch);
    await expect(signIn('proof')).rejects.toThrow(/permission/);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('does not loop if the backend rejects the location retry', async () => {
    locate.mockImplementation(success => success({ coords: { latitude: 0, longitude: 0, accuracy: 10 }, timestamp: Date.now() }));
    const fetch = vi.fn().mockResolvedValueOnce(locationRequired()).mockResolvedValueOnce(locationRequired());
    vi.stubGlobal('fetch', fetch);
    await expect(signOut('proof')).rejects.toMatchObject({ status: 403 });
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(locate).toHaveBeenCalledTimes(1);
  });
});
