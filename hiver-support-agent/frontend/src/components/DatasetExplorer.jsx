import React, { useState, useEffect } from 'react';
import { api } from '../api/client.js';

export default function DatasetExplorer() {
  const [historical, setHistorical] = useState([]);
  const [golden, setGolden] = useState([]);
  const [view, setView] = useState('historical');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([api.historicalSample(30), api.goldenSample(50)])
      .then(([h, g]) => { setHistorical(h); setGolden(g); })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <p>Loading dataset…</p>;

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <button onClick={() => setView('historical')} style={{ ...styles.tab, ...(view === 'historical' ? styles.tabActive : {}) }}>
          Historical Conversations (grounding corpus)
        </button>
        <button onClick={() => setView('golden')} style={{ ...styles.tab, ...(view === 'golden' ? styles.tabActive : {}) }}>
          Golden Evaluation Set
        </button>
      </div>

      {view === 'historical' && (
        <table style={styles.table}>
          <thead>
            <tr><th style={styles.th}>Intent</th><th style={styles.th}>Customer Text</th><th style={styles.th}>Brand Reply</th></tr>
          </thead>
          <tbody>
            {historical.map((h, i) => (
              <tr key={i}>
                <td style={styles.tdIntent}><span style={styles.pill}>{h.intent}</span></td>
                <td style={styles.td}>{h.customerText}</td>
                <td style={styles.td}>{h.brandReply}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {view === 'golden' && (
        <table style={styles.table}>
          <thead>
            <tr><th style={styles.th}>Group</th><th style={styles.th}>Text</th><th style={styles.th}>Gold Intent</th><th style={styles.th}>Escalate?</th><th style={styles.th}>Notes</th></tr>
          </thead>
          <tbody>
            {golden.map((g, i) => (
              <tr key={i}>
                <td style={styles.tdIntent}><span style={{ ...styles.pill, background: groupColor(g.sampleGroup) }}>{g.sampleGroup}</span></td>
                <td style={styles.td}>{g.customerText}</td>
                <td style={styles.td}>{g.goldIntent}</td>
                <td style={styles.td}>{g.goldEscalate ? 'Yes' : 'No'}</td>
                <td style={{ ...styles.td, color: '#888', fontStyle: 'italic' }}>{g.notes}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function groupColor(group) {
  if (group === 'hard_case') return '#ffd6d6';
  if (group === 'near_duplicate') return '#d6e8ff';
  return '#e5e5e5';
}

const styles = {
  tab: { padding: '8px 14px', background: '#f2f2f2', border: '1px solid #e0e0e0', borderRadius: 8, cursor: 'pointer', fontSize: 13 },
  tabActive: { background: '#d97757', color: 'white', border: '1px solid #d97757' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: 13 },
  th: { textAlign: 'left', padding: '8px 10px', borderBottom: '2px solid #ddd', color: '#666', fontSize: 11, textTransform: 'uppercase' },
  td: { padding: '8px 10px', borderBottom: '1px solid #eee', verticalAlign: 'top' },
  tdIntent: { padding: '8px 10px', borderBottom: '1px solid #eee', verticalAlign: 'top', whiteSpace: 'nowrap' },
  pill: { background: '#e5e5e5', padding: '2px 8px', borderRadius: 10, fontSize: 11, fontWeight: 600 },
};
