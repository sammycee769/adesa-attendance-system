import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './office-gateway.mjs';

const env = { VERCEL: '1', INGRESS_SHARED_SECRET: 'a'.repeat(64) };
function request(headers = {}) {
  const req = new Request('https://gateway.example/api/gateway?__adesa_path=v1/admin/network/client-address', { headers });
  return req;
}

test('replaces forged proof and XFF with the edge-observed IP; preserves auth cookies', async () => {
  const original = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, options) => {
    captured = { url, options };
    return new Response('{}', { headers: [
      ['Content-Type', 'application/json'],
      ['Set-Cookie', 'JSESSIONID=fixture; Secure; HttpOnly; SameSite=None'],
      ['Set-Cookie', 'XSRF-TOKEN=csrf-fixture; Secure; SameSite=None'],
    ] });
  };
  try {
    const result = await worker.fetch(request({
      'x-vercel-forwarded-for': '129.205.124.218', 'X-Adesa-Ingress-Key': 'forged',
      'X-Adesa-Client-IP': '105.127.8.52', 'X-Forwarded-For': '105.127.8.52',
      'Cookie': 'JSESSIONID=fixture', 'Origin': 'https://adesa-attendance-frontend.vercel.app',
      'X-Vercel-Protection-Bypass': 'private-bypass-fixture',
      'X-Vercel-Trusted-Oidc-Idp-Token': 'private-oidc-fixture',
    }), env);
    assert.equal(result.status, 200);
    assert.equal(captured.options.headers.get('X-Adesa-Client-IP'), '129.205.124.218');
    assert.equal(captured.options.headers.get('X-Adesa-Ingress-Key'), env.INGRESS_SHARED_SECRET);
    assert.equal(captured.options.headers.get('X-Forwarded-For'), null);
    assert.equal(captured.options.headers.get('X-Vercel-Protection-Bypass'), null);
    assert.equal(captured.options.headers.get('X-Vercel-Trusted-Oidc-Idp-Token'), null);
    assert.equal(captured.options.headers.get('Cookie'), 'JSESSIONID=fixture');
    assert.equal(captured.options.redirect, 'manual');
    assert.match(captured.url, /^https:\/\/adesa-attendance-system\.onrender\.com\/api\//);
    assert.equal(result.headers.get('X-Adesa-Ingress-Key'), null);
    assert.match(result.headers.get('Set-Cookie'), /JSESSIONID=fixture/);
    assert.equal(result.headers.getSetCookie().length, 2);
  } finally { globalThis.fetch = original; }
});

test('missing platform context, malformed IP and missing key fail closed', async () => {
  assert.equal((await worker.fetch(request({ 'x-vercel-forwarded-for': '105.127.8.52' }), { ...env, VERCEL: '' })).status, 403);
  for (const ip of ['', '105.127.8.52,129.205.124.218',
    '105.127.8.52/32', '999.1.2.3', '010.1.2.3']) {
    assert.equal((await worker.fetch(request({ 'x-vercel-forwarded-for': ip }), env)).status, 403);
  }
  assert.equal((await worker.fetch(request({ 'x-vercel-forwarded-for': '105.127.8.52' }), {})).status, 503);
});

test('IPv6 can reach account APIs but does not turn into an approved IPv4 assertion', async () => {
  const original = globalThis.fetch;
  const ips = [];
  globalThis.fetch = async (_url, options) => {
    ips.push(options.headers.get('X-Adesa-Client-IP'));
    return new Response('{}');
  };
  try {
    for (const ip of ['2001:db8::1', '2001:db8::2']) {
      assert.equal((await worker.fetch(request({ 'x-vercel-forwarded-for': ip }), env)).status, 200);
    }
    assert.deepEqual(ips, ['2001:db8::1', '2001:db8::2']);
  } finally { globalThis.fetch = original; }
});

test('origin redirects are not followed and private proof is not returned', async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response(null, {
    status: 302, headers: { Location: 'https://attacker.example/' },
  }); };
  try {
    const result = await worker.fetch(request({ 'x-vercel-forwarded-for': '105.127.8.52' }), env);
    assert.equal(result.status, 502);
    assert.equal(calls, 1);
    assert.equal(result.headers.get('Location'), null);
  } finally { globalThis.fetch = original; }
});

test('preserves CSRF, request body and query while routing only to the fixed API origin', async () => {
  const original = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, options) => {
    captured = { url, options, body: await new Response(options.body).text() };
    return new Response('decoded', { headers: { 'Content-Encoding': 'gzip', 'Content-Length': '999' } });
  };
  try {
    const req = new Request('https://frontend.example/api/gateway?__adesa_path=v1/auth/login&from=2026-10-23', {
      method: 'POST', headers: { 'x-vercel-forwarded-for': '105.127.8.52',
        'X-XSRF-TOKEN': 'csrf-fixture', 'Content-Type': 'application/json' },
      body: '{"email":"admin@example.test","password":"fixture"}',
    });
    const response = await worker.fetch(req, env);
    assert.equal(captured.url, 'https://adesa-attendance-system.onrender.com/api/v1/auth/login?from=2026-10-23');
    assert.match(captured.body, /admin@example.test/);
    assert.equal(captured.options.headers.get('X-XSRF-TOKEN'), 'csrf-fixture');
    assert.equal(response.headers.get('Content-Encoding'), null);
    assert.equal(response.headers.get('Content-Length'), null);
  } finally { globalThis.fetch = original; }
});

test('ambiguous or unexpected routing fails closed', async () => {
  for (const query of ['', '?__adesa_path=v1/auth/me&__adesa_path=v1/admin/employees',
    '?__adesa_path=https://attacker.example', '?__adesa_path=v1/../admin',
    '?__adesa_path=v1/%252e%252e/admin']) {
    const req = new Request('https://frontend.example/api/gateway' + query,
      { headers: { 'x-vercel-forwarded-for': '105.127.8.52' } });
    assert.equal((await worker.fetch(req, env)).status, 404);
  }
});
