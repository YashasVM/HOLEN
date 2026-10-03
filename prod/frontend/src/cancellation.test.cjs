// Run: node prod/frontend/src/cancellation.test.cjs (Node 22.13+).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { stripTypeScriptTypes } = require('node:module');
const source = fs.readFileSync(path.join(__dirname, 'DownloaderPage.tsx'), 'utf8');
const callbackSource = source.slice(source.indexOf('  const handleCancelJob ='), source.indexOf('  function undoClear'));

(async () => {
  for (const offline of [true, false]) {
    let jobs = [{ id: 'job', status: 'running' }];
    const apiFetch = async () => { if (offline) throw new Error('Offline'); return {}; };
    const cancel = new Function('useCallback', 'setJobs', 'apiFetch', 'showToast', 'friendlyError', 'applyJobsList',
      stripTypeScriptTypes(callbackSource) + '\nreturn handleCancelJob;')(
      fn => fn, update => jobs = update(jobs), apiFetch, () => {}, error => error.message, () => {},
    );
    await cancel('job');
    assert.equal(jobs[0].status, offline ? 'running' : 'cancelled');
  }
  const effectSource = source.slice(source.indexOf('  useEffect(() => {', source.indexOf('  const hasActiveJobs =')), source.indexOf('  useEffect(() => () => {'));
  let requests = 0;
  let scheduled;
  const browser = {
    setTimeout: callback => { scheduled = callback; return 1; },
    clearTimeout: () => { scheduled = undefined; },
    addEventListener() {}, removeEventListener() {},
  };
  new Function('useEffect', 'apiFetch', 'applyJobsList', 'setQueueError', 'friendlyError', 'hasActiveJobs', 'window', 'document',
    stripTypeScriptTypes(effectSource))(
    effect => effect(), async () => { requests++; throw new Error('Unavailable'); }, () => {}, () => {}, error => error.message,
    false, browser, { hidden: false, addEventListener() {}, removeEventListener() {} },
  );
  for (let attempt = 1; attempt <= 3; attempt++) {
    await new Promise(setImmediate);
    assert.equal(requests, attempt);
    if (attempt < 3) { assert.equal(typeof scheduled, 'function'); scheduled(); }
  }
  assert.equal(scheduled, undefined, 'Idle queue discovery must stop after three failed requests');
  console.log('Cancellation checks passed: failures preserve polling; successful cancellations update the card; idle retries stop after three requests.');
})().catch(error => { console.error(error); process.exitCode = 1; });
