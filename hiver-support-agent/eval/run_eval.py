#!/usr/bin/env python3
"""
run_eval.py — calls the running Spring Boot backend's /api/eval/run endpoint
and writes a human-readable metrics report to eval/metrics_report.md.

This is a thin CLI wrapper around the backend's evaluation harness
(EvaluationService.java) — all the actual metric computation happens in
Java so there's exactly one source of truth for the numbers. This script
just formats the result nicely and gives you something to paste into the
report / commit as evidence.

Usage:
    python3 eval/run_eval.py [--host http://localhost:8080] [--no-judge] [--judge-n 25]
"""
import argparse
import json
import os
import sys
import urllib.request


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fmt_pct(v):
    return f"{v * 100:.1f}%"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="http://localhost:8080")
    parser.add_argument("--no-judge", action="store_true")
    parser.add_argument("--judge-n", type=int, default=25)
    args = parser.parse_args()

    include_judge = "false" if args.no_judge else "true"
    url = f"{args.host}/api/eval/run?includeJudge={include_judge}&judgeSampleSize={args.judge_n}"
    print(f"Fetching {url} ...")
    try:
        data = fetch(url)
    except Exception as e:
        print(f"ERROR: could not reach backend at {args.host}. Is it running? ({e})", file=sys.stderr)
        sys.exit(1)

    lines = []
    lines.append(f"# Evaluation Report — {data['brand']}")
    lines.append("")
    lines.append(f"Golden set size: **{data['n_examples']}**")
    lines.append("")
    lines.append("## Intent Classification: main system vs. baselines")
    lines.append("")
    lines.append("| System | Accuracy | Macro-F1 |")
    lines.append("|---|---|---|")
    for key, name in [("trivial_baseline", "Trivial (majority class)"),
                       ("simple_baseline_keyword", "Simple (keyword matcher)"),
                       ("main_system", "Main system")]:
        d = data[key]
        lines.append(f"| {name} | {fmt_pct(d['accuracy'])} | {fmt_pct(d['macro_f1'])} |")
    lines.append("")

    lines.append("### Per-intent breakdown (main system)")
    lines.append("")
    lines.append("| Intent | Precision | Recall | F1 | Support |")
    lines.append("|---|---|---|---|---|")
    for intent, m in data["main_system"]["per_intent"].items():
        lines.append(f"| {intent} | {m['precision']} | {m['recall']} | {m['f1']} | {m['support']} |")
    lines.append("")

    esc = data["escalation_metrics"]
    lines.append("## Escalation decisioning")
    lines.append("")
    lines.append(f"Precision: **{fmt_pct(esc['precision'])}**  ·  Recall: **{fmt_pct(esc['recall'])}**  ·  F1: **{fmt_pct(esc['f1'])}**")
    lines.append("")
    lines.append(f"- True positives: {esc['true_positive']}")
    lines.append(f"- False positives (unnecessary escalation): {esc['false_positive_unnecessary_escalation']}")
    lines.append(f"- **False negatives (MISSED escalation — costliest error): {esc['false_negative_missed_escalation']}**")
    lines.append(f"- True negatives: {esc['true_negative']}")
    lines.append("")
    if esc["missed_escalations_detail"]:
        lines.append("### Missed escalations (should have escalated, system didn't)")
        for m in esc["missed_escalations_detail"]:
            lines.append(f"- `{m['text']}`  \n  reason given: _{m['system_reason_given']}_")
        lines.append("")

    judge = data["llm_judge"]
    lines.append("## LLM-as-judge: reply quality")
    lines.append("")
    if judge.get("status") == "skipped":
        lines.append(f"_Skipped: {judge['reason']}_")
    else:
        lines.append(f"n judged: {judge['n_judged']}")
        lines.append("")
        lines.append("| Relevance | Groundedness | Correctness | Tone |")
        lines.append("|---|---|---|---|")
        lines.append(f"| {judge['mean_relevance']}/5 | {judge['mean_groundedness']}/5 | {judge['mean_correctness']}/5 | {judge['mean_tone']}/5 |")
    lines.append("")

    report = "\n".join(lines)
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "metrics_report.md")
    with open(out_path, "w") as f:
        f.write(report)

    print(report)
    print(f"\nWrote report to {out_path}")


if __name__ == "__main__":
    main()
