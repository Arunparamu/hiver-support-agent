import React, { useState } from 'react';
import { api } from '../api/client.js';

const SAMPLE_MESSAGES = [
  "@ShopHelp where is my order #542981? placed it 5 days ago and tracking hasn't moved",
  "@ShopHelp I returned item from order 118822 over 2 weeks ago and still no refund, this is ridiculous",
  "@ShopHelp this is the LAST time I order from you, my lawyer will be in touch about order 553201",
  "@ShopHelp just wanted to say your support team was super helpful today, thank you!",
  "@ShopHelp I was charged twice for order 993201, please refund the duplicate charge immediately",
];

export default function AgentPlayground() {
  const [message, setMessage] = useState(SAMPLE_MESSAGES[0]);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit(e) {
    e?.preventDefault();
    if (!message.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.process(message);
      setResult(res);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <form onSubmit={handleSubmit} style={{ marginBottom: 20 }}>
        <label style={styles.label}>Incoming customer message</label>
        <textarea
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          rows={3}
          style={styles.textarea}
          placeholder="Paste or type a customer tweet…"
        />
        <div style={{ display: 'flex', gap: 8, marginTop: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          <button type="submit" disabled={loading} style={styles.primaryButton}>
            {loading ? 'Processing…' : 'Run Agent'}
          </button>
          <span style={{ fontSize: 13, color: '#888' }}>or try:</span>
          {SAMPLE_MESSAGES.map((s, i) => (
            <button
              key={i}
              type="button"
              onClick={() => setMessage(s)}
              style={styles.chip}
              title={s}
            >
              sample {i + 1}
            </button>
          ))}
        </div>
      </form>

      {error && <div style={styles.errorBox}>{error}</div>}

      {result && <ResultPanel result={result} />}
    </div>
  );
}

function ResultPanel({ result }) {
  const { classification, reply, escalation } = result;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
      <Card title="1. Intent Classification">
        <BigValue value={classification.intent} />
        <Row label="Confidence" value={classification.confidence} />
        <Row label="Method" value={classification.method} />
        {classification.topIntents?.length > 0 && (
          <div style={{ marginTop: 8 }}>
            <div style={styles.subLabel}>Ranked alternatives</div>
            <ul style={styles.ul}>
              {classification.topIntents.map((t, i) => <li key={i} style={styles.li}>{t}</li>)}
            </ul>
          </div>
        )}
      </Card>

      <Card title="3. Escalation Decision" accent={escalation.escalate ? '#a12622' : '#1a7431'}>
        <BigValue value={escalation.escalate ? 'ESCALATE TO HUMAN' : 'AUTO-HANDLE'} color={escalation.escalate ? '#a12622' : '#1a7431'} />
        <div style={{ marginTop: 8, fontSize: 13, lineHeight: 1.5 }}>{escalation.reason}</div>
        {escalation.triggeredRules?.length > 0 && (
          <div style={{ marginTop: 8 }}>
            <div style={styles.subLabel}>Triggered rules</div>
            <ul style={styles.ul}>
              {escalation.triggeredRules.map((r, i) => <li key={i} style={styles.li}>{r}</li>)}
            </ul>
          </div>
        )}
      </Card>

      <Card title="2. Drafted Reply" wide>
        <div style={styles.replyBox}>{reply.draftReply}</div>
        <Row label="Method" value={reply.method} />
        {reply.groundedOn?.length > 0 && (
          <div style={{ marginTop: 10 }}>
            <div style={styles.subLabel}>Grounded on {reply.groundedOn.length} historical resolutions</div>
            {reply.groundedOn.map((ex, i) => (
              <div key={i} style={styles.groundedItem}>
                <div style={{ fontSize: 12, color: '#888' }}>similarity {ex.similarity} · intent: {ex.intent}</div>
                <div style={{ fontSize: 13, marginTop: 2 }}><em>Customer:</em> {ex.customerText}</div>
                <div style={{ fontSize: 13, marginTop: 2 }}><em>Brand replied:</em> {ex.brandReply}</div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}

function Card({ title, children, wide, accent }) {
  return (
    <div style={{ ...styles.card, gridColumn: wide ? '1 / -1' : undefined, borderTop: accent ? `3px solid ${accent}` : styles.card.borderTop }}>
      <div style={styles.cardTitle}>{title}</div>
      {children}
    </div>
  );
}

function BigValue({ value, color }) {
  return <div style={{ fontSize: 20, fontWeight: 700, color: color || '#1c1c1e' }}>{value}</div>;
}

function Row({ label, value }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginTop: 6 }}>
      <span style={{ color: '#888' }}>{label}</span>
      <span style={{ fontWeight: 500 }}>{String(value)}</span>
    </div>
  );
}

const styles = {
  label: { display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6, color: '#444' },
  textarea: { width: '100%', boxSizing: 'border-box', padding: 12, borderRadius: 8, border: '1px solid #ddd', fontSize: 14, fontFamily: 'inherit', resize: 'vertical' },
  primaryButton: { padding: '10px 20px', background: '#d97757', color: 'white', border: 'none', borderRadius: 8, fontWeight: 600, cursor: 'pointer', fontSize: 14 },
  chip: { padding: '6px 10px', background: '#f2f2f2', border: '1px solid #e0e0e0', borderRadius: 14, fontSize: 12, cursor: 'pointer' },
  errorBox: { padding: 12, background: '#fde2e1', color: '#a12622', borderRadius: 8, marginBottom: 16, fontSize: 13 },
  card: { background: '#fafafa', border: '1px solid #eee', borderTop: '3px solid #d97757', borderRadius: 10, padding: 16 },
  cardTitle: { fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.5, color: '#888', marginBottom: 10 },
  subLabel: { fontSize: 11, fontWeight: 700, textTransform: 'uppercase', color: '#aaa', marginTop: 4 },
  ul: { margin: '4px 0 0', paddingLeft: 18 },
  li: { fontSize: 12, color: '#555', marginBottom: 2 },
  replyBox: { padding: 12, background: 'white', border: '1px solid #e5e5e5', borderRadius: 8, fontSize: 14, lineHeight: 1.5 },
  groundedItem: { padding: 8, background: 'white', border: '1px solid #eee', borderRadius: 6, marginTop: 6 },
};
