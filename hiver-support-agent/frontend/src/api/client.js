const BASE = '/api';

async function handle(res) {
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json();
}

export const api = {
  status: () => fetch(`${BASE}/agent/status`).then(handle),

  process: (message) =>
    fetch(`${BASE}/agent/process`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    }).then(handle),

  historicalSample: (limit = 10) =>
    fetch(`${BASE}/agent/historical-sample?limit=${limit}`).then(handle),

  goldenSample: (limit = 20) =>
    fetch(`${BASE}/agent/golden-sample?limit=${limit}`).then(handle),

  runEval: (includeJudge = true, judgeSampleSize = 25) =>
    fetch(`${BASE}/eval/run?includeJudge=${includeJudge}&judgeSampleSize=${judgeSampleSize}`).then(handle),
};
