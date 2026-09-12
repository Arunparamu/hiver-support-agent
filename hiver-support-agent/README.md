# ShopHelp AI Support Agent

A take-home submission for the Hiver SDE Intern assignment: an AI support agent for one brand's
Twitter customer support that (1) classifies incoming messages into intents, (2) drafts a reply
grounded in how the brand has historically resolved similar issues, and (3) decides auto-handle vs.
escalate-to-human, with a stated reason.

**Stack:** Spring Boot 3 (Java 17) backend, React + Vite frontend, plain-CSV data layer, hand-rolled
TF-IDF retrieval (no vector DB needed at this scale), pluggable LLM client (OpenAI-compatible HTTP API).

**Read this first:** `report/REPORT.md` section "What's misleading about my headline number?" and the
"Dataset & its limitations" section below — the honest constraints on this submission live there, not
buried in an appendix.

---

## Repo layout

```
hiver-support-agent/
├── backend/                  Spring Boot API (classification, retrieval, drafting, escalation, eval)
├── frontend/                 React app: Agent Playground, Dataset Explorer, Evaluation Dashboard
├── data/                     Dataset generation script + generated CSVs (source of truth)
├── eval/                     Standalone eval CLI + human/judge agreement tooling
├── report/                   REPORT.md (main report) + DECISION_LOG.md
└── docs/                     Architecture notes
```

## Dataset & its limitations — read before trusting any number below

The real Kaggle "Customer Support on Twitter" dataset (`thoughtvector/customer-support-on-twitter`,
~3M tweets) requires a Kaggle account/API token to download. **This build environment has no network
access**, so it could not be downloaded here. Rather than skip the deliverable, `data/generate_dataset.py`
produces a dataset that is **structurally identical** to the real one (same conceptual fields: tweet id,
inbound/outbound, timestamp, text, in-reply-to) and **style-matched** to real retail-brand support
threads (order/delivery/refund dominate the volume; tone ranges from neutral to angry; brand replies are
short, apologetic, and redirect PII to DM — this is the real, well-documented behavior of handles like
`@AmazonHelp` / `@AppleSupport` on Twitter). **It is not scraped real data.**

This is a real limitation, not a rounding error, and it is the single most important caveat on every
metric in this repo — see `report/REPORT.md`.

**To swap in the real data** (recommended before treating any result here as production evidence):
1. Download `twcs.csv` from Kaggle.
2. Filter to one brand's `author_id` (e.g. `AmazonHelp`).
3. Reshape into `data/historical_conversations.csv` and `data/golden_eval_set.csv` (same columns — see
   the header row of each file, or the docstring at the top of `generate_dataset.py`).
4. Point `backend/src/main/resources/application.yml` `data.conversations-path` / `data.golden-path` at
   the new files. **No code changes required** — the loader just reads CSV by path.

## Golden evaluation set — how it was built

`data/golden_eval_set.csv`, 201 examples, three sample groups (see the `sample_group` column):

- **`proportional` (160 examples):** stratified sample matching the natural intent distribution of the
  historical corpus (e.g. ~22% order_status, ~18% delivery_issue, down to ~1% "other"). Held out —
  generated from the same templates as the historical set but not copied from it.
- **`hard_case` (10 examples):** hand-authored, not templated. These are the cases that matter most for
  trust even though they're rare: legal threats, a self-harm signal, multi-intent messages, meta-complaints
  about the support process itself, and messages that look actionable but aren't (or vice versa). Pure
  random sampling under-represents exactly these cases.
- **`near_duplicate` (30 examples):** paraphrases of historical-corpus patterns, used to check whether the
  system is doing genuine classification/retrieval or just pattern-matching near-identical strings.

Each row has `gold_intent` and `gold_escalate`, assigned during construction. **Labeling methodology
caveat:** because the underlying data is synthetic (see above), "gold" labels here reflect the intent the
template was designed to represent, not independent human judgment on ambiguous real text. The 10
`hard_case` examples ARE independently reasoned through (see the `notes` column for the labeling
rationale on each). If you swap in real Kaggle data, re-label the golden set by hand against the actual
text — this is flagged explicitly in `report/REPORT.md`.

## Quickstart — reproduce in under 15 minutes

**Prerequisites:** Java 17+, Maven, Node 18+, npm. (An LLM API key is optional — see below.)

### 1. Run the backend (from `backend/`)

```bash
cd backend
mvn spring-boot:run
```

Backend starts on `http://localhost:8080`. It loads the CSVs at startup — no database needed.

Health check: `curl http://localhost:8080/api/agent/status`

**No LLM key needed to run the full pipeline.** By default (`LLM_API_KEY` unset), classification falls
back to a keyword baseline and reply drafting falls back to adapting the single best-matching historical
reply. This is what makes "reproduce in 15 minutes" true regardless of whether you have API credits —
see `application.yml` comments and `report/REPORT.md` for exactly what changes when a key IS set.

**To enable the LLM-backed classifier / drafter / judge**, set before starting the backend:

```bash
export LLM_API_KEY=sk-...
export LLM_BASE_URL=https://api.openai.com/v1   # or any OpenAI-compatible endpoint
export LLM_MODEL=gpt-4o-mini
mvn spring-boot:run
```

### 2. Run the frontend (from `frontend/`, in a second terminal)

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Three tabs:
- **Agent Playground** — paste a customer message, see classification → draft reply → escalation
  decision, with the retrieved grounding examples shown inline.
- **Dataset Explorer** — browse the historical conversations and the golden eval set.
- **Evaluation Dashboard** — click "Run Evaluation Harness" to compute all metrics live against the
  golden set (trivial baseline vs. simple baseline vs. main system, escalation P/R, and LLM-judge scores
  if a key is configured) and inspect missed escalations / judged examples in place.

### 3. (Optional) CLI evaluation + report generation

```bash
python3 eval/run_eval.py                      # writes eval/metrics_report.md
python3 eval/run_eval.py --no-judge            # skip LLM-judge (faster, no API cost)
python3 eval/run_eval.py --judge-n 200         # judge more of the golden set
```

### 4. (Optional) Human/judge agreement check

`eval/human_judge_agreement_sample.csv` has 10 replies independently scored by a human on the same 4-axis
rubric the LLM judge uses. To compare:

```bash
# with LLM_API_KEY set and backend running:
curl "http://localhost:8080/api/eval/run?includeJudge=true&judgeSampleSize=200" > eval/judge_output.json
python3 eval/compute_judge_agreement.py
```

This repo does **not** ship a pre-computed `judge_output.json` — generating one requires a live API key,
which this build environment doesn't have. Fabricating agreement numbers without actually running the
judge would defeat the point of the exercise; see the honesty note at the top of
`eval/compute_judge_agreement.py`.

## API reference (backend)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/agent/process` | `{"message": "..."}` → classification + draft reply + escalation decision |
| GET | `/api/agent/status` | brand, LLM-enabled flag, dataset sizes |
| GET | `/api/agent/historical-sample?limit=N` | sample of the grounding corpus |
| GET | `/api/agent/golden-sample?limit=N` | sample of the golden eval set |
| GET | `/api/eval/run?includeJudge=bool&judgeSampleSize=N` | full evaluation harness |

## Report & decision log

- [`report/REPORT.md`](report/REPORT.md) — problem framing, baselines, failure analysis, "what's
  misleading about my headline number", next steps.
- [`report/DECISION_LOG.md`](report/DECISION_LOG.md) — 14 non-obvious decisions and why.
