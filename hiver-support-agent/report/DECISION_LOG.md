# Decision Log

Plain list of non-obvious decisions made while building this, and why. Ordered roughly by when they came
up, not by importance.

1. **Used a synthetic, template-generated dataset instead of the real Kaggle corpus.** This build
   environment has no network egress, so Kaggle's API (which requires auth + a network call) was
   unreachable. Rather than submit an unrunnable pipeline or silently pretend the data was real, I built
   a generator (`data/generate_dataset.py`) that mirrors the real dataset's schema and the real, publicly-
   documented behavior of retail brand-support Twitter handles, and documented the substitution loudly in
   the README and REPORT rather than burying it. Alternative considered: manually transcribing ~200 real
   tweets from memory — rejected because that risks misattributing invented text to a real brand handle,
   which is worse than a clearly-labeled synthetic stand-in.

2. **Made the entire pipeline runnable with zero API key.** Every LLM-backed component
   (`IntentClassifierService`, `ReplyDraftService`, `EvaluationService`'s judge) has a deterministic,
   non-LLM fallback, selected automatically based on whether `LLM_API_KEY` is set. This was a direct
   response to the "README must let us reproduce results in under 15 minutes" requirement — a grader
   without an API key (or without wanting to spend one on a take-home) should still see the full system
   work end to end. Trade-off, stated explicitly in the report: "main system" and "simple baseline" are
   byte-identical in fallback mode, which is a real honesty risk if not flagged — so it's flagged twice
   (README and REPORT).

3. **Hand-rolled TF-IDF instead of pulling in a vector-search library or calling an embeddings API.** At
   ~260 historical examples, a full vector DB (Pinecone/pgvector/etc.) is overkill and adds a dependency
   the grader would need to stand up. A ~150-line TF-IDF + cosine-similarity implementation is fast enough
   at this scale, has zero external dependencies, and is fully auditable in one file
   (`util/TfIdfVectorizer.java`). Would not make this choice at 100k+ examples — noted as a "next week"
   scaling consideration.

4. **Escalation decisioning is rule-based, not LLM-judged, even though an LLM is available for
   classification and drafting.** Escalation is the safety-critical gate (a missed escalation on a legal
   threat or self-harm signal is a much worse failure than a wrong intent label). Rules are: (a) auditable
   — you can read exactly why a message triggered escalation, (b) don't depend on LLM availability/latency
   for a safety-critical path, (c) don't silently drift if a model is swapped. I accepted the cost that
   rules have blind spots to paraphrase (see REPORT failure mode #4) in exchange for these properties.

5. **Escalation rules run independent of predicted intent**, not as a per-intent lookup table. A billing
   dispute that also contains a legal threat must escalate even if the classifier's top label is
   `billing_dispute` (which is already in the "usually escalate" list) — but the safety-keyword check
   fires as an unconditional first gate before anything intent-specific, so it can't be masked by a
   different, lower-priority rule matching first.

6. **Confidence-based escalation threshold (0.55) was picked as an educated guess, not calibrated.**
   Explicitly logged as a known gap rather than dressed up as validated — see REPORT "next week" item 4.
   I considered not implementing a confidence threshold at all until it could be calibrated, but decided a
   stated, honestly-flagged guess is more useful than omitting the mechanism entirely, since the mechanism
   itself (escalate on low confidence) is the right shape even if the exact number isn't tuned.

7. **Golden set intentionally over-samples rare/hard cases (hard_case + near_duplicate groups) rather than
   pure random/proportional sampling.** A pure proportional sample of ~200 examples from a distribution
   where legal-threat and self-harm messages are <1% of volume would likely contain zero of them — exactly
   the cases where a miss is most costly. Trade-off: this makes "accuracy on the golden set" not a
   representative estimate of accuracy on live traffic; it's explicitly a stress-test-weighted set, and
   that's called out in both the README and REPORT so the number isn't misread as representative.

8. **Positive class for escalation metrics is "should escalate," not "should auto-handle."** This makes
   recall = "fraction of true escalation-worthy messages we actually caught," which is the number that
   matters for trust (a missed escalation is worse than an unnecessary one). If I'd used auto-handle as
   the positive class, a system that escalates everything would score a misleadingly-bad-looking recall
   despite being maximally safe — the metric choice needed to match which error type is actually costly.

9. **LLM-as-judge scores 4 separate axes (relevance, groundedness, correctness, tone) instead of one
   overall quality score.** A single 1-5 "quality" score collapses distinct failure modes (a reply can be
   perfectly on-brand in tone while completely wrong about the refund policy) into a number that hides
   which axis is failing. Cost: 4x the judge-parsing complexity for a small eval sample; worth it for
   the failure-analysis section to actually be diagnostic rather than "some replies scored low, unclear why."

10. **Did not fabricate LLM-judge/human-agreement numbers when no API key was available to run them.** I
    built the full measurement workflow (`eval/compute_judge_agreement.py`,
    `eval/human_judge_agreement_sample.csv` with 10 independently human-scored replies) but left the
    actual agreement statistic unreported rather than inventing a plausible-sounding kappa. This was the
    single decision I went back and forth on most — a fabricated "κ=0.81, strong agreement" would have
    made the report read as more complete, but would be exactly the unverifiable claim this assignment
    exists to catch.

11. **Reply drafting fallback (no LLM key) reuses the single best-matching historical reply nearly
    verbatim, rather than templating/filling slots.** Considered building a slot-filling template system
    (detect order number in the new message, substitute it into the retrieved reply) — rejected as scope
    creep for a fallback path whose entire purpose is "the honest floor of zero-model-calls behavior," not
    a second drafting system to maintain and evaluate separately.

12. **Multi-intent detection is a crude keyword-family counter (`countLikelyIntentSignals`), not a proper
    multi-label classifier.** A real multi-label classifier needs multi-label training data, which doesn't
    exist here. The crude heuristic (count how many of 5 keyword families appear) is enough to catch the
    most damaging case — a message escalates instead of getting a single-intent auto-reply when it looks
    like it might be about more than one thing — without overclaiming multi-label classification quality
    anywhere in the report.

13. **Used plain CSV files as the entire data layer, no database.** For ~260 historical + ~200 golden
    examples, a database adds setup friction (schema migrations, a DB the grader needs to run) for zero
    benefit at this scale. CSV also makes the "swap in real Kaggle data" story in the README trivial: it's
    a file-format match, not a schema migration.

14. **Kept intent taxonomy at 10 categories instead of adopting Banking77's 77 categories.** Banking77 is
    banking-domain and far more fine-grained than what a retail-brand support account's real intent
    distribution looks like (order/delivery/refund dominate; there's no natural retail equivalent of many
    Banking77 categories like "card_swallowed"). Adopting 77 categories for a retail brand would produce a
    taxonomy where most categories have near-zero support in the actual data — precision/recall on them
    would be meaningless. 10 categories were derived by open-coding a first pass of ~80 example messages,
    merging near-duplicate categories (e.g. "wrong item" folded into `product_defect`), and dropping
    anything under ~2% support.

15. **Classification "method" field is exposed in every API response** (`"llm"` vs `"keyword_baseline"` vs
    `"keyword_baseline_llm_error"`), rather than hidden behind the scenes. This was a direct response to
    decision #2's honesty risk — if fallback mode is silently indistinguishable from LLM mode in the UI, a
    grader could easily misattribute keyword-baseline behavior to the LLM. Making the method visible in
    both the API and the Agent Playground UI closes that gap.
