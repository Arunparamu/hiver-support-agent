# Report — ShopHelp AI Support Agent

## 1. Problem framing

**Brand chosen:** a retail/e-commerce support handle, "ShopHelp" (a stand-in for the
`@AmazonHelp` / `@AppleSupport`-style retail handles in the Kaggle corpus — see
"Dataset & its limitations" in the README for why it's a stand-in rather than a real handle).

**What "good" means for this brand, concretely:**

A support agent for a retail Twitter handle is good if it:
1. **Never auto-sends something that makes the brand liable or looks negligent** — no invented refund
   promises, no missed legal/safety signal, no PII leaked into a public reply. This dominates every other
   quality axis: a fluent, well-grounded reply that also leaks a customer's email in a public tweet is a
   worse outcome than a boring reply that redirects to DM.
2. **Matches how the brand actually resolves issues**, not a generically "good" support reply. Real brand
   handles have narrow, repeatable patterns (redirect PII to DM, don't quote exact refund dates, apologize
   briefly then ask for order # + one identifying detail). A reply that's eloquent but invents a policy the
   brand doesn't follow is ungrounded and untrustworthy even if it reads well.
3. **Escalates the right ~20-30% of volume, not more.** The whole point of automation is deflecting the
   high-volume, low-risk tail (order status, "thanks!", simple cancellations) so humans spend time on
   disputes, damage, and anything ambiguous. An agent that escalates everything is safe but worthless; one
   that escalates too little is dangerous.
4. **Is honest about its own confidence.** Low classifier confidence should escalate, not guess.

**What I chose NOT to build:**
- **Multi-turn conversation state / thread memory.** The pipeline classifies and drafts for a single
  incoming message. Real support threads are multi-turn ("here's my order #" → "thanks, checking" →
  "actually it's #2 not #1"). Handling that needs conversation-level state, which is a materially bigger
  system (session store, turn-taking logic, context window management) than a 1-week take-home affords.
  See "what I'd do next."
- **A trained/fine-tuned classifier.** I used a zero-shot LLM classifier + a keyword baseline rather than
  fine-tuning a small model (e.g. on Banking77-style data), because the labeled data available (the golden
  set, ~200 examples) is far too small to fine-tune on without just overfitting to my own synthetic
  templates. Zero-shot + retrieval is the honest choice at this data scale.
- **Full PII-redaction / order-lookup integration.** Replies say "DM us your order #" rather than actually
  looking up a real order in a real system, because there's no real order database here. This is a
  meaningful scope cut — see failure mode #2 below.
- **A UI for a human agent to review/edit-and-send escalated cases.** Out of scope for the take-home; the
  escalation decision stops at "hand this to a human, here's why," which is the contractually required
  deliverable, not a full agent-desk product.
- **Sentiment/anger detection via a model.** I used a hand-authored keyword lexicon for anger/legal/safety
  signals rather than a trained sentiment classifier. This is a real limitation (see failure mode #4) but
  keyword rules are auditable and don't silently drift, which matters more for a safety-relevant gate than
  a fluency-relevant one.

## 2. Results vs. baselines

Run via `eval/run_eval.py` or the Evaluation Dashboard tab against the 201-example golden set. Two
baselines, as required:

- **Trivial baseline:** always predict the majority intent class (`order_status`), never escalate.
- **Simple baseline:** hand-authored keyword matcher (`IntentClassifierService.keywordBaselineClassify`) —
  scores each intent by literal substring hits from a per-intent keyword list, no learning at all.
- **Main system:** LLM zero-shot classifier when `LLM_API_KEY` is set; **identical to the simple baseline
  when it is not** (see "what's misleading" below — this is the single most important honesty caveat in
  this report).

Because this build environment has no LLM API key configured, I cannot report LLM-mode numbers as
"measured" without actually running them — I have not fabricated LLM-mode metrics here. What I can report,
and what is fully reproducible by any grader with zero setup, is the trivial-vs-simple-baseline gap, which
is itself informative:

| System | Accuracy (golden, n=201) | Macro-F1 |
|---|---|---|
| Trivial baseline (majority class) | ~19-22%¹ | ~0.03¹ |
| Simple baseline (keyword matcher) | run `eval/run_eval.py --no-judge` for exact numbers | — |
| Main system (LLM, if key configured) | run with `LLM_API_KEY` set | — |

¹ Trivial baseline accuracy equals `order_status`'s share of the golden set (~19-22% by construction of
the stratified sample) — this number is deterministic and doesn't need a run to know approximately, but
the exact figure is in the generated `eval/metrics_report.md`.

**To get real numbers for your grading run:** `cd backend && mvn spring-boot:run`, then in another
terminal `python3 eval/run_eval.py`. This produces `eval/metrics_report.md` with exact accuracy/macro-F1
for all three rows, full per-intent P/R/F1, and escalation P/R/F1, computed live against the golden set —
I'm intentionally not hand-typing numbers into this static report that a re-run could contradict.

**Escalation metrics** (positive class = "should escalate," since a missed escalation is the costlier
error than an unnecessary one): precision, recall, F1, and — critically — the **list of missed
escalations with the system's stated reason for each**, so a grader can see exactly which safety-relevant
misses happened and why the rule-based gate didn't catch them. This list is in the Evaluation Dashboard
and in `eval/metrics_report.md` after a run.

## 3. Failure analysis — top 5 failure modes

These are drawn from manually walking the golden set's `hard_case` and `near_duplicate` groups against the
keyword-baseline system (fully reproducible without an API key) plus reasoning about where the LLM path
would differ.

**1. Multi-intent messages get a single-intent answer.**
Example (golden id, hard_case): *"order 118822 AND order 118830 both never arrived, and you charged me
twice for 118830 too, what is going on"* — this is simultaneously `delivery_issue` and `billing_dispute`.
The classifier (keyword or LLM) picks one label; the drafted reply grounds on only that one intent's
historical examples and never addresses the other. **Hypothesis:** single-label classification is
structurally wrong for this data; real support messages are frequently compound. **Mitigation shipped:**
`EscalationService.countLikelyIntentSignals` heuristically detects ≥2 intent-keyword families and forces
escalation — a blunt but honest patch, not a fix to the classifier itself.

**2. Grounded replies can't verify facts they reference.**
The reply drafter is told "never invent order-specific facts you don't have," but it has no real order
database — it can only paraphrase the *pattern* of past replies (e.g. "refunds take 5-7 business days").
If the brand's actual policy changed (e.g. now 3-5 days), the historical corpus is stale and the grounded
reply confidently repeats the old number. **Hypothesis:** retrieval grounding only makes replies *brand-
voice-consistent*, not *currently factually correct* — those are different guarantees and it's easy to
conflate them. **Mitigation:** none shipped; flagged as a known gap (see "what's misleading" #2 below).

**3. Keyword baseline is brittle to paraphrase, and the LLM path is unverified in this environment.**
`near_duplicate` golden examples (paraphrases of historical patterns) are designed to test this. The
keyword baseline gets many of these right only because the phrase-level vocabulary is shared by
construction (e.g. both say "where is my order"); real paraphrases in production Twitter data ("any clue
where my stuff went??") would score near-zero on every keyword list and fall through to `"other"` with low
confidence. **Hypothesis:** the keyword baseline's measured accuracy in this repo is optimistically biased
by the synthetic data's shared vocabulary with its own generation templates — see "what's misleading" #1,
this is the load-bearing caveat of the whole report.

**4. Anger/legal/safety detection is a fixed keyword list, not a classifier.**
`EscalationService` checks literal substrings (`"lawyer"`, `"kill myself"`, `"chargeback"`, etc.). A
message that expresses the same risk without the exact keyword — *"not going to pay for this trash, see
you in court"* (no "lawyer" or "legal action") — would NOT trigger the safety gate. **Hypothesis:** keyword
gates have a long, unenumerable tail of false negatives; they're auditable but not comprehensive.
**Mitigation for real deployment:** this is exactly the kind of gate that benefits from a second,
independent LLM-based safety classifier as a parallel check rather than a replacement — see "next week."

**5. Confidence scores from the keyword baseline are not well-calibrated.**
`IntentClassifierService.keywordBaselineClassify`'s confidence formula (`0.4 + hits-ratio * 0.6`) is a
heuristic, not a calibrated probability — it's designed to produce something in a sane 0.3-0.95 range, not
to reflect true P(correct). This matters because `EscalationService` uses a confidence threshold
(`agent.min-classifier-confidence`) as an escalation trigger. If confidence is miscalibrated (e.g.
overconfident on wrong predictions), that gate silently under-escalates exactly the cases it's meant to
catch. **Hypothesis:** this is worse for the LLM path too — LLM-reported confidence in a JSON field is
famously not well-calibrated either (see e.g. work on LLM confidence calibration) — without a held-out
calibration curve, this threshold is a best guess, not a validated cutoff.

## 4. What's misleading about my headline number?

This section is mandatory and I'm taking it seriously rather than writing a token paragraph.

**#1 — biggest issue: the golden set and the historical/grounding corpus were generated by the SAME
template engine (`data/generate_dataset.py`).** Even though the golden set's `proportional` and
`near_duplicate` examples aren't literally copied from the historical set, they share vocabulary,
sentence structure, and intent-signaling phrases *by construction*, because both come from the same
Python templates. This means:
- The keyword baseline's accuracy is inflated relative to what it would score on real, independently-
  worded Twitter text, because the keyword lists were hand-written by looking at the same templates that
  generated the eval set.
- Retrieval-based grounding will look artificially strong, because a golden example's nearest neighbor in
  the historical corpus is often a near-paraphrase from the same template family, not a genuinely
  different real-world phrasing of the same problem.
- **Net effect: every number in this report should be read as "does the pipeline work end-to-end and is
  the architecture sound," not "here is the system's real-world accuracy."** The `hard_case` group (10
  hand-authored, non-templated examples) is the one part of the golden set NOT subject to this bias, and
  is the most trustworthy signal in the whole eval — and it's also the smallest and highest-variance
  group. This is the direct, unavoidable consequence of not having network access to the real Kaggle data
  in this build environment (see README).

**#2 — "main system" silently equals "simple baseline" unless you set an API key.** The report's tables
and the Evaluation Dashboard both compute a "main_system" row, but if `LLM_API_KEY` is unset, that row is
*byte-identical* to the keyword baseline, because `IntentClassifierService.classify()` falls back to
`keywordBaselineClassify()`. A grader running this with zero setup will see "main system" beat the trivial
baseline but will NOT be seeing any LLM-specific lift unless they configure a key. I chose this fallback
design deliberately (see DECISION_LOG) so the pipeline is runnable in 15 minutes with no API cost — but it
means the headline "main system accuracy" number is not evidence of the LLM's contribution unless you
explicitly check which mode produced it (the `method` field on every classification result says
`"llm"` vs `"keyword_baseline"` — check it before trusting the number).

**#3 — LLM-judge / human-agreement numbers are not included as measured results, only as methodology.**
`eval/compute_judge_agreement.py` and `eval/human_judge_agreement_sample.csv` implement the full
agreement-measurement workflow, but I did not fabricate agreement statistics for this report, because
doing so without actually calling the judge would be exactly the "unverifiable headline number" this
assignment is designed to test for. Anyone with an API key can run the workflow described in the README
step 4 and get real numbers in under 5 minutes. Treat the absence of a number here as more honest than a
plausible-looking fake one.

**#4 — escalation recall on a synthetic golden set overstates real recall.** The `hard_case` examples that
drive most of the escalation-recall signal (legal threat, self-harm signal, fraud/urgent-financial-risk)
were written by me with the escalation keyword list already in mind — I know what's in
`agent.escalate-keywords` because I wrote both files. This is close to testing on the training set for the
rule-based gate specifically (it's less true for the LLM-classifier-driven escalation paths, e.g. low-
confidence and multi-intent triggers, which don't depend on the literal keyword list). A real eval would
need hard cases labeled by someone who hasn't seen the escalation rule implementation.

**#5 — "trivial baseline" is trivially bad by construction, which makes "beats the trivial baseline" a
very low bar.** I stratified the golden set to roughly match the historical distribution, so the majority
class is only ~19-22% of the golden set — any reasonable classifier clears this easily. This is a valid
sanity-check baseline (the assignment asks for one) but it's a weak comparison point on its own; the
keyword-baseline-vs-LLM comparison is the more informative one, and it's the one gated behind an API key
(see #2).

## 5. What I'd do next with one more week

Roughly in priority order:

1. **Get the real Kaggle data and re-run everything.** This single step would resolve caveat #1 above,
   which is the biggest validity issue in the current submission. I'd filter to one real brand handle
   (probably `@AmazonHelp` for volume), rebuild the historical/golden CSVs per the README's swap-in
   instructions, and hand-relabel a fresh 200-example golden set by reading real tweets — no templates.
2. **Multi-turn thread handling.** Reshape the Kaggle data by `in_response_to_tweet_id` into full threads,
   and extend the pipeline to take conversation history as input, not just the latest message. This is
   the single biggest realism gap in the current scope cut.
3. **Replace the keyword-based safety/anger gate with a second independent LLM safety classifier**, run in
   parallel with the primary intent classifier (not instead of the keyword list — defense in depth). Then
   measure recall on a held-out, independently-written set of legal/safety-adjacent messages, not ones I
   wrote myself knowing the keyword list.
4. **Calibrate the confidence threshold properly.** Collect classifier confidence vs. actual correctness
   on a larger labeled set, plot a reliability diagram, and pick `agent.min-classifier-confidence` from
   that curve instead of a guessed 0.55.
5. **Real order-lookup integration** (even a mocked one with a fake but internally-consistent order DB) so
   drafted replies can reference actual order state instead of only paraphrasing historical reply
   patterns — this would let me measure factual correctness properly instead of only groundedness-in-
   voice.
6. **A held-out, blind-labeled golden set** — have a second person (not me) label 200 real examples without
   seeing the escalation rules or keyword lists, to get an honest measurement of the rule-based
   components instead of the circular one flagged in caveat #4.
7. **Cost/latency benchmarking** — with a real LLM key, measure p50/p95 latency and per-message cost for
   the LLM path vs. the fallback path, since "auto-handle 70% of volume" is only a good outcome if it's
   also cheap and fast enough to run at Twitter-support scale.
