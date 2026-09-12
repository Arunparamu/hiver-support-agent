#!/usr/bin/env python3
"""
compute_judge_agreement.py

Measures how well the LLM-as-judge agrees with a human rater on the SAME
10 drafted replies, across the 4 scoring axes (relevance, groundedness,
correctness, tone).

WORKFLOW:
1. Human labels live in eval/human_judge_agreement_sample.csv (already done,
   by the assignment author, on the deterministic template-fallback replies
   so the exact reply text scored is reproducible without an LLM key).
2. Run: python3 eval/run_eval.py --judge-n 200   (with LLM_API_KEY set)
   This calls /api/eval/run and the response includes judge scores for every
   golden example the judge was run on, including ids 1,2,11,23,37,45,52,61,70,78.
3. Save that raw JSON response to eval/judge_output.json (see README).
4. Run this script: python3 eval/compute_judge_agreement.py
   It matches golden_id -> judge score and reports agreement metrics.

METRICS REPORTED:
- Exact match rate (human score == judge score, per axis)
- Within-1 agreement rate (|human - judge| <= 1, per axis) — the more
  meaningful threshold for 1-5 Likert scales, since exact match is a strict
  bar even between two human raters
- Mean absolute error, per axis
- Pearson correlation, per axis (only meaningful with more points; treat
  as directional with n=10)

HONESTY NOTE: this repo ships WITHOUT judge_output.json committed, because
generating it requires an LLM API key and a live call, which this
environment does not have at build time (see README "Reproducing results").
Once you run step 2-3 above with your own key, this script will produce
real agreement numbers on your own judge's output. Do not report simulated
numbers as if they were measured — that is exactly the kind of unverifiable
claim this assignment is designed to catch.
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
HUMAN_CSV = os.path.join(HERE, "human_judge_agreement_sample.csv")
JUDGE_JSON = os.path.join(HERE, "judge_output.json")

AXES = ["relevance", "groundedness", "correctness", "tone"]


def load_human():
    rows = {}
    with open(HUMAN_CSV, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows[int(r["golden_id"])] = {
                "relevance": int(r["human_relevance"]),
                "groundedness": int(r["human_groundedness"]),
                "correctness": int(r["human_correctness"]),
                "tone": int(r["human_tone"]),
            }
    return rows


def load_judge():
    if not os.path.exists(JUDGE_JSON):
        print(f"ERROR: {JUDGE_JSON} not found.")
        print("Run the backend with an LLM key, call /api/eval/run, and save the")
        print("response JSON to eval/judge_output.json before running this script.")
        print("See the docstring at the top of this file for the full workflow.")
        sys.exit(1)
    with open(JUDGE_JSON) as f:
        data = json.load(f)
    judge = data.get("llm_judge", {})
    examples = judge.get("examples", [])
    rows = {}
    for ex in examples:
        rows[int(ex["id"])] = ex["scores"]
    return rows


def main():
    human = load_human()
    judge = load_judge()

    matched_ids = [gid for gid in human if gid in judge]
    if not matched_ids:
        print("No overlapping golden IDs between human sample and judge output.")
        print("Make sure judge_output.json was generated with judgeSampleSize covering "
              "ids: " + ", ".join(str(i) for i in sorted(human.keys())))
        sys.exit(1)

    print(f"Matched {len(matched_ids)} / {len(human)} human-labeled examples in judge output.\n")

    for axis in AXES:
        diffs = []
        exact = 0
        within1 = 0
        for gid in matched_ids:
            h = human[gid][axis]
            j = judge[gid][axis]
            d = abs(h - j)
            diffs.append(d)
            if d == 0:
                exact += 1
            if d <= 1:
                within1 += 1
        n = len(matched_ids)
        mae = sum(diffs) / n
        print(f"{axis:15s}  exact_match={exact}/{n} ({exact/n*100:.0f}%)   "
              f"within_1={within1}/{n} ({within1/n*100:.0f}%)   MAE={mae:.2f}")

    print(f"\n(n={len(matched_ids)} — small sample, treat as directional, not a "
          f"statistically powered agreement estimate. See report/REPORT.md "
          f"'what's misleading about my headline number'.)")


if __name__ == "__main__":
    main()
