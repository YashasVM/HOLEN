// Run from prod/frontend:
// ./node_modules/.bin/tsc src/lib/api.ts --outDir /tmp/holen-api-check --module commonjs --target ES2020 --lib ES2020,DOM --skipLibCheck
// node src/lib/api.test.cjs /tmp/holen-api-check/lib/api.js
const assert = require('node:assert/strict');
const { requestJson } = require(process.argv[2]);
(async () => {
  let calls = 0;
  const options = [];
  const getToken = async (option) => { options.push(option); return option.skipCache ? 'fresh' : 'cached'; };
  global.fetch = async (_, init) => {
    calls++;
    assert.equal(init.headers.get('Authorization'), calls === 1 ? 'Bearer cached' : 'Bearer fresh');
    return new Response(JSON.stringify(calls === 1 ? { detail: 'Expired' } : { id: 'job' }), { status: calls === 1 ? 401 : 200 });
  };
  assert.deepEqual(await requestJson('/api/jobs', getToken, { method: 'POST', body: '{}' }), { id: 'job' });
  assert.equal(calls, 2);
  assert.deepEqual(options, [{ skipCache: false }, { skipCache: true }]);
  calls = 0;
  global.fetch = async () => { calls++; throw new TypeError('Network down'); };
  await assert.rejects(requestJson('/api/jobs', getToken, { method: 'POST', body: '{}' }), /Connection interrupted/);
  assert.equal(calls, 1, 'Network failures must not duplicate queued jobs');
  global.fetch = async () => new Response('<html>Starting</html>', { status: 503 });
  await assert.rejects(requestJson('/api/jobs', getToken), /Server unavailable \(503\)/);
  global.fetch = async () => new Response('');
  await assert.rejects(requestJson('/api/jobs', getToken), /incomplete response/);
  global.fetch = async () => { throw new DOMException('Timeout', 'TimeoutError'); };
  await assert.rejects(requestJson('/api/jobs', getToken), /too long/);
  console.log('API regression checks passed: token refresh, no duplicate mutations, cold-start HTML, truncated JSON, timeout.');
})().catch((error) => { console.error(error); process.exitCode = 1; });
