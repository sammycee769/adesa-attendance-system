const ORIGIN = 'https://adesa-attendance-system.onrender.com';
const KEY = 'X-Adesa-Ingress-Key';
const IP = 'X-Adesa-Client-IP';

function deny(status) {
  return new Response('Gateway request could not be verified', {
    status, headers: { 'Cache-Control': 'no-store' },
  });
}

function ipv4(value) {
  return typeof value === 'string'
    && /^(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}$/.test(value)
    && value.split('.').every(part => Number(part) <= 255);
}

function clientAddress(value) {
  if (ipv4(value)) return true;
  if (typeof value !== 'string' || !/^[0-9a-fA-F:]+$/.test(value) || !value.includes(':')) return false;
  try { return new URL(`http://[${value}]/`).hostname.startsWith('['); }
  catch { return false; }
}

export default {
  async fetch(request, env = process.env) {
    if (!/^[0-9a-f]{64}$/.test(env.INGRESS_SHARED_SECRET ?? '')) return deny(503);
    const url = new URL(request.url);
    if (url.protocol !== 'https:') return deny(400);
    const paths = url.searchParams.getAll('__adesa_path');
    if (paths.length !== 1 || !/^v1\/[A-Za-z0-9._/-]+$/.test(paths[0])
        || paths[0].includes('..')) return deny(404);
    const apiPath = '/api/' + paths[0];
    url.searchParams.delete('__adesa_path');
    // Only Vercel supplies the trusted incoming header. No local/public proxy fallback.
    if (env.VERCEL !== '1') return deny(403);
    const clientIp = request.headers.get('x-vercel-forwarded-for');
    // IPv6 may administer/sign in; the IPv4-only backend policy rejects attendance.
    if (!clientAddress(clientIp)) return deny(403);
    const headers = new Headers(request.headers);
    for (const name of [KEY, IP, 'X-Forwarded-For', 'True-Client-IP',
      'X-Real-IP', 'Forwarded', 'CF-Connecting-IP', 'x-vercel-forwarded-for', 'Host',
      'Connection', 'Transfer-Encoding', 'Keep-Alive', 'Upgrade', 'TE', 'Trailer',
      'Proxy-Authorization', 'Proxy-Authenticate', 'Content-Length']) headers.delete(name);
    headers.set(KEY, env.INGRESS_SHARED_SECRET);
    headers.set(IP, clientIp);
    headers.set('Accept-Encoding', 'identity');
    const target = new URL(ORIGIN);
    target.pathname = apiPath;
    target.search = url.search;
    try {
      const upstream = await fetch(target.toString(), {
        method: request.method, headers,
        body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
        redirect: 'manual', duplex: 'half', signal: AbortSignal.timeout(240000),
      });
      // Do not follow a backend redirect with cookies or the private ingress key.
      if (upstream.status >= 300 && upstream.status < 400) return deny(502);
      const response = new Response(upstream.body, upstream);
      response.headers.delete(KEY);
      response.headers.delete(IP);
      // Node fetch decodes any compressed upstream body; Vercel may recompress it.
      response.headers.delete('Content-Encoding');
      response.headers.delete('Content-Length');
      response.headers.set('Cache-Control', 'no-store');
      return response;
    } catch {
      // Never log request headers, session cookies, credentials or bodies.
      return deny(502);
    }
  },
};
