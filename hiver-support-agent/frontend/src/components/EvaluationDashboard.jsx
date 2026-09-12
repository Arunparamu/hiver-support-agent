import React, { useState } from 'react';
import { api } from '../api/client.js';

export default function EvaluationDashboard() {
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [includeJudge, setIncludeJudge] = useState(true);
  const [judgeSampleSize, setJudgeSampleSize] = useState(25);

  async function runEval() {
    setLoading(true);
    setError(null);
    try {
      const res = await api.runEval(includeJudge, judgeSampleSize);
      setResult(res);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <div style={styles.controls}>
        <button onClick={runEval} disabled={loading} style={styles.primaryButton}>
          {loading ? 'Running evaluation…' : 'Run Evaluation Harness'}
        </button>
        <label style={{ fontSize: 13 }}>
          <input type="checkbox" checked={includeJudge} onChange={(e) => setIncludeJudge(e.target.checked)} /> Include LLM-as-judge
        </label>
        <label style={{ fontSize: 13 }}>
          Judge sample size:{' '}
          <input
            type="number"
            value={judgeSampleSize}
            onChange={(e) => setJudgeSampleSize(Number(e.target.value))}
            style={{ width: 60 }}
          />
        </label>
      </div>
      <p style={styles.hint}>
        Runs against the {result?.n_examples ?? '~200'}-example golden set: a trivial baseline (always predict
        the majority intent), a simple keyword baseline, and the main system — plus escalation precision/recall
        and (if an LLM key is configured) LLM-as-judge reply-quality scores.
      </p>

      {error && <div style={styles.errorBox}>{error}</div>}

      {result && (
        <div>
          <ClassificationComparison result={result} />
          <EscalationPanel result={result.escalation_metrics} />
          <JudgePanel result={result.llm_judge} />
        </div>
      )}
    </div>
  );
}

function ClassificationComparison({ result }) {
  const rows = [
    { name: 'Trivial baseline (majority class)', data: result.trivial_baseline },
    { name: 'Simple baseline (keyword matcher)', data: result.simple_baseline_keyword },
    { name: 'Main system (LLM if configured, else = simple baseline)', data: result.main_system },
  ];
  return (
    <Section title="Intent Classification: Main System vs. Baselines">
      <table style={styles.table}>
        <thead>
          <tr><th style={styles.th}>System</th><th style={styles.th}>Accuracy</th><th style={styles.th}>Macro-F1</th></tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              <td style={styles.td}>{r.name}</td>
              <td style={styles.tdMetric}>{fmtPct(r.data.accuracy)}</td>
              <td style={styles.tdMetric}>{fmtPct(r.data.macro_f1)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <details style={{ marginTop: 12 }}>
        <summary style={styles.summary}>Per-intent breakdown (main system)</summary>
        <table style={{ ...styles.table, marginTop: 8 }}>
          <thead>
            <tr><th style={styles.th}>Intent</th><th style={styles.th}>Precision</th><th style={styles.th}>Recall</th><th style={styles.th}>F1</th><th style={styles.th}>Support</th></tr>
          </thead>
          <tbody>
            {Object.entries(result.main_system.per_intent).map(([intent, m]) => (
              <tr key={intent}>
                <td style={styles.td}>{intent}</td>
                <td style={styles.tdMetric}>{m.precision}</td>
                <td style={styles.tdMetric}>{m.recall}</td>
                <td style={styles.tdMetric}>{m.f1}</td>
                <td style={styles.tdMetric}>{m.support}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </Section>
  );
}

function EscalationPanel({ result }) {
  if (!result) return null;
  return (
    <Section title="Escalation Decisioning (positive class = 'should escalate' — a missed escalation is the costlier error)">
      <div style={styles.metricRow}>
        <Metric label="Precision" value={fmtPct(result.precision)} />
        <Metric label="Recall" value={fmtPct(result.recall)} />
        <Metric label="F1" value={fmtPct(result.f1)} />
        <Metric label="Missed escalations (FN)" value={result.false_negative_missed_escalation} color="#a12622" />
        <Metric label="Unnecessary escalations (FP)" value={result.false_positive_unnecessary_escalation} color="#8a5a00" />
      </div>
      {result.missed_escalations_detail?.length > 0 && (
        <details style={{ marginTop: 12 }}>
          <summary style={styles.summary}>⚠️ {result.missed_escalations_detail.length} missed escalations — see examples</summary>
          {result.missed_escalations_detail.map((m, i) => (
            <div key={i} style={styles.missBox}>
              <div style={{ fontSize: 13 }}>{m.text}</div>
              <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>System's stated reason: "{m.system_reason_given}"</div>
            </div>
          ))}
        </details>
      )}
    </Section>
  );
}

function JudgePanel({ result }) {
  if (!result) return null;
  if (result.status === 'skipped') {
    return (
      <Section title="LLM-as-Judge: Reply Quality">
        <p style={{ fontSize: 13, color: '#888' }}>{result.reason}</p>
      </Section>
    );
  }
  return (
    <Section title={`LLM-as-Judge: Reply Quality (n=${result.n_judged})`}>
      <div style={styles.metricRow}>
        <Metric label="Relevance" value={`${result.mean_relevance} / 5`} />
        <Metric label="Groundedness" value={`${result.mean_groundedness} / 5`} />
        <Metric label="Correctness" value={`${result.mean_correctness} / 5`} />
        <Metric label="Tone" value={`${result.mean_tone} / 5`} />
      </div>
      <details style={{ marginTop: 12 }}>
        <summary style={styles.summary}>Judged examples</summary>
        {result.examples.map((ex, i) => (
          <div key={i} style={styles.missBox}>
            <div style={{ fontSize: 13 }}><strong>Customer:</strong> {ex.customer_text}</div>
            <div style={{ fontSize: 13, marginTop: 4 }}><strong>Draft:</strong> {ex.draft_reply}</div>
            <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
              rel {ex.scores.relevance} · ground {ex.scores.groundedness} · correct {ex.scores.correctness} · tone {ex.scores.tone} — {ex.scores.rationale}
            </div>
          </div>
        ))}
      </details>
    </Section>
  );
}

function Section({ title, children }) {
  return (
    <div style={styles.section}>
      <h3 style={styles.sectionTitle}>{title}</h3>
      {children}
    </div>
  );
}

function Metric({ label, value, color }) {
  return (
    <div style={styles.metric}>
      <div style={{ fontSize: 20, fontWeight: 700, color: color || '#1c1c1e' }}>{value}</div>
      <div style={{ fontSize: 11, color: '#888', textTransform: 'uppercase' }}>{label}</div>
    </div>
  );
}

function fmtPct(v) {
  return `${(v * 100).toFixed(1)}%`;
}

const styles = {
  controls: { display: 'flex', alignItems: 'center', gap: 16, marginBottom: 8, flexWrap: 'wrap' },
  hint: { fontSize: 12, color: '#888', marginBottom: 20, maxWidth: 700 },
  primaryButton: { padding: '10px 20px', background: '#d97757', color: 'white', border: 'none', borderRadius: 8, fontWeight: 600, cursor: 'pointer', fontSize: 14 },
  errorBox: { padding: 12, background: '#fde2e1', color: '#a12622', borderRadius: 8, marginBottom: 16, fontSize: 13 },
  section: { marginBottom: 28, padding: 16, background: '#fafafa', border: '1px solid #eee', borderRadius: 10 },
  sectionTitle: { fontSize: 14, margin: '0 0 12px' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: 13 },
  th: { textAlign: 'left', padding: '6px 10px', borderBottom: '2px solid #ddd', color: '#666', fontSize: 11, textTransform: 'uppercase' },
  td: { padding: '6px 10px', borderBottom: '1px solid #eee' },
  tdMetric: { padding: '6px 10px', borderBottom: '1px solid #eee', fontVariantNumeric: 'tabular-nums' },
  summary: { fontSize: 13, cursor: 'pointer', color: '#d97757', fontWeight: 600 },
  metricRow: { display: 'flex', gap: 24, flexWrap: 'wrap' },
  metric: { minWidth: 100 },
  missBox: { padding: 10, background: 'white', border: '1px solid #eee', borderRadius: 6, marginTop: 8 },
};
