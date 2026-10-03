import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('./functions/api/[[path]].js', import.meta.url), 'utf8');
const { onRequest } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const originalFetch = globalThis.fetch;
let forwarded;
globalThis.fetch = async (request) => {
  forwarded = request;
  return new Response('file', { headers: { 'Content-Type': 'application/octet-stream' } });
};
const context = (path, headers = {}, method = 'GET') => ({
  request: new Request(`https://frontend.example${path}`, { headers, method }),
  env: { API_UPSTREAM_ORIGIN: 'https://origin.example' },
});
try {
  assert.equal((await onRequest(context('/api/jobs'))).status, 401);
  assert.equal((await onRequest(context('/api/jobs', { cookie: 'holen_download_ticket=test' }))).status, 401);
  assert.equal((await onRequest(context('/api/jobs/id/download', { cookie: 'unrelated=test' }))).status, 401);
  const response = await onRequest(context('/api/jobs/id/download', { cookie: 'holen_download_ticket=test', range: 'bytes=2-' }));
  assert.equal(response.status, 200);
  assert.equal(forwarded.url, 'https://origin.example/api/jobs/id/download');
  assert.equal(forwarded.headers.get('range'), 'bytes=2-');
  assert.equal(forwarded.headers.get('cookie'), 'holen_download_ticket=test');
  assert.equal(await response.text(), 'file');
  assert.equal((await onRequest(context('/api/jobs/id/download', { cookie: 'holen_download_ticket=test' }, 'HEAD'))).status, 200);
  assert.equal(forwarded.method, 'HEAD');
  assert.equal((await onRequest(context('/api/health'))).status, 200);
  assert.equal((await onRequest({ ...context('/api/health'), env: { API_UPSTREAM_ORIGIN: 'https://frontend.example' } })).status, 503);
  globalThis.fetch = async () => { throw new Error('offline'); };
  assert.equal((await onRequest(context('/api/health'))).status, 502);
  console.log('API proxy checks passed');
} finally {
  globalThis.fetch = originalFetch;
}
