import React, { useState, useEffect } from 'react';
import { api } from './api/client.js';
import AgentPlayground from './components/AgentPlayground.jsx';
import EvaluationDashboard from './components/EvaluationDashboard.jsx';
import DatasetExplorer from './components/DatasetExplorer.jsx';

export default function App() {
  const [tab, setTab] = useState('playground');
  const [status, setStatus] = useState(null);
  const [statusError, setStatusError] = useState(null);

  useEffect(() => {
    api.status().then(setStatus).catch((e) => setStatusError(e.message));
  }, []);

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <div>
          <h1 style={styles.title}>ShopHelp AI Support Agent</h1>
          <p style={styles.subtitle}>Intent classification · grounded reply drafting · escalation decisioning</p>
        </div>
        <StatusBadge status={status} error={statusError} />
      </header>

      <nav style={styles.nav}>
        {[
          { id: 'playground', label: 'Agent Playground' },
          { id: 'dataset', label: 'Dataset Explorer' },
          { id: 'eval', label: 'Evaluation Dashboard' },
        ].map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            style={{ ...styles.navButton, ...(tab === t.id ? styles.navButtonActive : {}) }}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <main style={styles.main}>
        {tab === 'playground' && <AgentPlayground />}
        {tab === 'dataset' && <DatasetExplorer />}
        {tab === 'eval' && <EvaluationDashboard />}
      </main>

      <footer style={styles.footer}>
        Hiver SDE Intern take-home — synthetic ShopHelp dataset (see README for real-data swap instructions)
      </footer>
    </div>
  );
}

function StatusBadge({ status, error }) {
  if (error) return <span style={{ ...styles.badge, background: '#fde2e1', color: '#a12622' }}>backend unreachable</span>;
  if (!status) return <span style={{ ...styles.badge, background: '#eee', color: '#666' }}>connecting…</span>;
  return (
    <div style={{ display: 'flex', gap: 8 }}>
      <span style={{ ...styles.badge, background: '#e3f5e6', color: '#1a7431' }}>
        {status.historicalConversationCount} historical · {status.goldenExampleCount} golden
      </span>
      <span style={{ ...styles.badge, background: status.llmEnabled ? '#e3f5e6' : '#fff4e0', color: status.llmEnabled ? '#1a7431' : '#8a5a00' }}>
        {status.llmEnabled ? 'LLM mode' : 'Fallback mode (no LLM key)'}
      </span>
    </div>
  );
}

const styles = {
  page: { fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif', maxWidth: 1100, margin: '0 auto', padding: '24px 20px', color: '#1c1c1e' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 20, flexWrap: 'wrap', gap: 12 },
  title: { fontSize: 24, margin: 0 },
  subtitle: { fontSize: 14, color: '#666', margin: '4px 0 0' },
  badge: { padding: '6px 12px', borderRadius: 20, fontSize: 12, fontWeight: 600, whiteSpace: 'nowrap' },
  nav: { display: 'flex', gap: 8, marginBottom: 24, borderBottom: '1px solid #e5e5e5', paddingBottom: 0 },
  navButton: { padding: '10px 16px', background: 'none', border: 'none', borderBottom: '2px solid transparent', cursor: 'pointer', fontSize: 14, fontWeight: 500, color: '#666' },
  navButtonActive: { borderBottom: '2px solid #d97757', color: '#d97757' },
  main: { minHeight: 400 },
  footer: { marginTop: 40, paddingTop: 16, borderTop: '1px solid #e5e5e5', fontSize: 12, color: '#999', textAlign: 'center' },
};
