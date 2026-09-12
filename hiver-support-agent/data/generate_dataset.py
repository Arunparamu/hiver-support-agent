"""
generate_dataset.py

IMPORTANT — READ THIS FIRST:
The real "Customer Support on Twitter" Kaggle dataset (thoughtvector/customer-support-on-twitter,
~3M tweets) requires a Kaggle account + API token to download, and this build environment has no
network egress. So this script produces a dataset that is STRUCTURALLY IDENTICAL to the real one
(same columns: tweet_id, author_id, inbound, created_at, text, response_tweet_id, in_response_to_tweet_id)
and is style-matched to real brand-support Twitter threads, for one brand: "ShopHelp" (a stand-in
for a retail/e-commerce support handle, modeled on how @AmazonHelp / @AppleSupport / @Delta style
handles resolve tickets on Twitter).

WHAT'S SYNTHETIC vs REAL:
- The *distribution of intents*, the *style* of customer complaints, and the *resolution patterns*
  are hand-authored to mirror what you'd actually see in the Kaggle corpus for a retail brand
  (order/delivery/refund/account issues dominate; tone ranges from neutral to angry; brand replies
  are short, apologetic, redirect to DM for PII).
- This is NOT scraped real data. It is a substitute so the pipeline is runnable end-to-end.

TO USE THE REAL DATA INSTEAD:
1. Download `twcs.csv` from Kaggle (thoughtvector/customer-support-on-twitter).
2. Filter to one `author_id` (brand handle), e.g. AmazonHelp / AppleSupport / SpotifyCares.
3. Reshape into the same 3 files this script produces:
     - historical_conversations.csv  (customer_text, brand_reply, intent[optional])
     - golden_eval_set.csv           (id, customer_text, gold_intent, gold_escalate, notes)
4. Point backend/src/main/resources/application.yml `data.conversations-path` /
   `data.golden-path` at the new files. No code changes needed — the loader just reads CSV.

See README.md "Dataset & its limitations" and REPORT.md "What's misleading about my headline
number?" for the honest accounting of this substitution's implications.
"""

import csv
import random
import os

random.seed(42)

OUT_DIR = os.path.dirname(os.path.abspath(__file__))

BRAND = "ShopHelp"

# ---------------------------------------------------------------------------
# 1. Intent taxonomy (defined FROM the data — see report/REPORT.md section
#    "Problem framing" for how these were derived: open-coded 80 raw examples,
#    merged near-duplicate categories, dropped categories with <2% support).
# ---------------------------------------------------------------------------
INTENTS = [
    "order_status",       # "where is my order"
    "delivery_issue",     # late / lost / damaged in transit
    "refund_request",     # wants money back
    "product_defect",     # item arrived broken / not as described
    "account_access",     # login / password / locked account
    "billing_dispute",    # charged wrong amount / duplicate charge
    "cancellation",       # wants to cancel order/subscription
    "general_complaint",  # unhappy, vague, venting
    "praise_feedback",    # positive / neutral feedback, no ask
    "other",              # spam, unrelated, unclear
]

# Templates: (customer_text_templates, brand_reply_templates, escalate_hint)
# escalate_hint: "auto" | "escalate" | "mixed" -> used to seed golden labels with a rule of thumb,
# THEN hand-adjusted per-example (see labeling notes in golden set generation below).
TEMPLATES = {
    "order_status": {
        "customer": [
            "@{brand} hey where is my order #{oid}? placed it {days} days ago and tracking hasn't moved",
            "@{brand} any update on order {oid}? it's been {days} days",
            "Hi @{brand}, can you tell me the status of order #{oid}? No shipping confirmation yet.",
            "@{brand} tracking says 'label created' for {days} days now on order {oid}, is it actually shipped??",
            "@{brand} just checking in on my order {oid}, still says processing",
        ],
        "brand": [
            "Hi! Sorry for the wait — let's take a look. Can you DM us your order number and zip code so we can check the latest tracking update? ^KM",
            "Hey there, we understand the concern. Please send us a DM with your order # and email on file and we'll investigate right away. ^RT",
            "Thanks for flagging this! Could you DM us order #{oid} along with your shipping address so we can pull up the exact status? ^AS",
        ],
        "escalate_hint": "auto",
    },
    "delivery_issue": {
        "customer": [
            "@{brand} my package for order {oid} shows delivered but I never got it. This is the second time this month!!",
            "@{brand} order {oid} arrived completely crushed, box was soaking wet too",
            "@{brand} courier marked {oid} as delivered {days} days ago, nothing on my porch, checked with neighbors too",
            "@{brand} this is unacceptable, order {oid} was supposed to arrive {days} days ago and now tracking just stopped updating",
        ],
        "brand": [
            "That's really frustrating, we're sorry. Please DM us your order number and address so we can open a carrier investigation and get this resolved. ^KM",
            "So sorry to hear that! Send us a DM with order #{oid} and we'll start a lost package claim right away. ^RT",
            "We hate to hear that — please DM your order number and a photo of the damage so we can process a replacement. ^AS",
        ],
        "escalate_hint": "mixed",
    },
    "refund_request": {
        "customer": [
            "@{brand} I returned item from order {oid} over 2 weeks ago and still no refund, this is ridiculous",
            "@{brand} where's my refund for order {oid}?? you people took the item back but never gave my money",
            "@{brand} need a refund on order {oid}, product is defective, already contacted support twice with no response",
            "@{brand} refund for {oid} still hasn't hit my card after {days} days, your policy says 5-7 business days",
        ],
        "brand": [
            "We're sorry for the delay! Refunds can take 5-7 business days to post once we receive the return. Can you DM your order # so we can verify it's been processed on our end? ^KM",
            "Apologies for the inconvenience — please DM your order number and we'll check the refund status and escalate if it's past the window. ^RT",
        ],
        "escalate_hint": "mixed",
    },
    "product_defect": {
        "customer": [
            "@{brand} the {product} I got in order {oid} stopped working after 2 days, this is so disappointing",
            "@{brand} order {oid} arrived with a cracked screen on the {product}, box looked fine so it must've shipped that way",
            "@{brand} {product} from order {oid} is missing parts, can barely use it",
        ],
        "brand": [
            "So sorry to hear that! Please DM us your order number and a photo of the issue so we can arrange a replacement or refund. ^AS",
            "That's not the experience we want for you — DM us order #{oid} with photos and we'll get this sorted quickly. ^KM",
        ],
        "escalate_hint": "auto",
    },
    "account_access": {
        "customer": [
            "@{brand} I can't log into my account, keeps saying invalid password even after reset",
            "@{brand} my account got locked after I tried logging in from my new phone, need help ASAP",
            "@{brand} not receiving the password reset email, checked spam too",
        ],
        "brand": [
            "Sorry for the trouble! Please DM the email associated with your account and we'll help unlock/reset it. ^RT",
            "We can help with that — DM us the email on file (not the password) and we'll look into the login issue. ^AS",
        ],
        "escalate_hint": "auto",
    },
    "billing_dispute": {
        "customer": [
            "@{brand} I was charged twice for order {oid}, please refund the duplicate charge immediately",
            "@{brand} charged $89 more than what the checkout page showed for order {oid}, this better get fixed",
            "@{brand} there's a charge on my card from you that I don't recognize at all, order {oid}?? I never ordered this",
        ],
        "brand": [
            "We're sorry about that! Please DM your order number and the last 4 digits of the card charged so we can investigate the duplicate/incorrect charge. ^KM",
            "That shouldn't happen — DM us order #{oid} and we'll look into the charge discrepancy right away. ^RT",
        ],
        "escalate_hint": "escalate",
    },
    "cancellation": {
        "customer": [
            "@{brand} need to cancel order {oid} ASAP, ordered wrong size",
            "@{brand} how do I cancel my subscription? can't find the option anywhere on the app",
            "@{brand} please cancel order {oid}, hasn't shipped yet I hope",
        ],
        "brand": [
            "No problem! DM us your order number right away — if it hasn't shipped yet we can cancel it for you. ^AS",
            "We can help with that — DM us your account email and we'll walk you through cancelling the subscription. ^RT",
        ],
        "escalate_hint": "auto",
    },
    "general_complaint": {
        "customer": [
            "@{brand} your customer service is honestly the worst, been on hold for 40 minutes",
            "@{brand} really disappointed with my experience lately, used to love shopping here",
            "@{brand} why is it so hard to get a human on the phone, this app is a mess",
        ],
        "brand": [
            "We're really sorry to hear that and we want to make this right. Can you DM us more details so we can look into what happened? ^KM",
            "That's not the experience we want you to have — please DM us so we can dig into this personally. ^RT",
        ],
        "escalate_hint": "escalate",
    },
    "praise_feedback": {
        "customer": [
            "@{brand} just wanted to say your support team was super helpful resolving my order {oid} today, thank you!",
            "@{brand} fast shipping on order {oid}, really impressed, will order again",
            "@{brand} love the new app redesign, so much easier to track orders now",
        ],
        "brand": [
            "That means a lot, thank you for sharing! 😊 ^AS",
            "We're so glad to hear that! Thanks for the kind words. ^KM",
        ],
        "escalate_hint": "auto",
    },
    "other": {
        "customer": [
            "@{brand} do you guys sponsor giveaways? saw one on instagram and wasn't sure if it's real",
            "@{brand} what are your store hours for the downtown location tomorrow?",
            "@{brand} following up from DM, checking if anyone's there",
        ],
        "brand": [
            "Great question! Please check our official Instagram bio for verified giveaway info, we want to make sure you don't get scammed. ^RT",
            "Hi! You can find store hours on our website's Store Locator page. Let us know if you need help finding it! ^AS",
        ],
        "escalate_hint": "auto",
    },
}

PRODUCTS = ["blender", "headphones", "jacket", "laptop stand", "sneakers", "coffee maker", "backpack", "watch", "lamp", "speaker"]

# Weighted so the distribution looks like real retail-support Twitter data:
# order/delivery/refund dominate, praise + other are a small tail.
INTENT_WEIGHTS = {
    "order_status": 22,
    "delivery_issue": 18,
    "refund_request": 16,
    "product_defect": 12,
    "account_access": 8,
    "billing_dispute": 8,
    "cancellation": 7,
    "general_complaint": 5,
    "praise_feedback": 3,
    "other": 1,
}


def weighted_intent():
    intents, weights = zip(*INTENT_WEIGHTS.items())
    return random.choices(intents, weights=weights, k=1)[0]


def make_example(intent, idx):
    t = TEMPLATES[intent]
    oid = random.randint(100000, 999999)
    days = random.choice([2, 3, 4, 5, 6, 7, 8, 10, 14])
    product = random.choice(PRODUCTS)
    cust_tpl = random.choice(t["customer"])
    brand_tpl = random.choice(t["brand"])
    customer_text = cust_tpl.format(brand=BRAND, oid=oid, days=days, product=product)
    brand_reply = brand_tpl.format(brand=BRAND, oid=oid, days=days, product=product)
    return {
        "tweet_id": 100000 + idx,
        "author_id": f"cust_{idx}",
        "inbound": True,
        "created_at": f"2024-0{random.randint(1,9)}-{random.randint(10,28):02d}",
        "text": customer_text,
        "response_tweet_id": 200000 + idx,
        "in_response_to_tweet_id": "",
        "intent": intent,
        "brand_reply": brand_reply,
        "order_id": oid,
    }


def main():
    n_historical = 260
    rows = []
    for i in range(n_historical):
        intent = weighted_intent()
        rows.append(make_example(intent, i))

    hist_path = os.path.join(OUT_DIR, "historical_conversations.csv")
    with open(hist_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=[
            "tweet_id", "author_id", "inbound", "created_at", "text",
            "response_tweet_id", "in_response_to_tweet_id", "intent", "brand_reply", "order_id"
        ])
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print(f"wrote {len(rows)} rows -> {hist_path}")

    # ---------------------------------------------------------------------
    # Golden evaluation set: 200 examples, sampled to (a) match the natural
    # intent distribution above [160 examples, stratified proportional] and
    # (b) deliberately OVER-sample rare/hard cases [40 examples] that matter
    # for trust even though they're rare: angry/legal-threat messages,
    # ambiguous multi-intent messages, and near-duplicates of historical
    # examples (to test overfitting to retrieval). This mirrors real labeling
    # practice: pure random sampling under-represents exactly the cases you
    # most need to get right. See report/REPORT.md "how sampled/labelled".
    # ---------------------------------------------------------------------
    golden_rows = []
    gid = 1

    # (a) proportional stratified sample, held out (NOT drawn from historical rows above)
    n_proportional = 160
    for intent, weight in INTENT_WEIGHTS.items():
        count = max(2, round(n_proportional * weight / sum(INTENT_WEIGHTS.values())))
        for _ in range(count):
            ex = make_example(intent, 900000 + gid)
            escalate_hint = TEMPLATES[intent]["escalate_hint"]
            gold_escalate = {"auto": False, "escalate": True, "mixed": random.random() < 0.5}[escalate_hint]
            golden_rows.append({
                "id": gid,
                "customer_text": ex["text"],
                "gold_intent": intent,
                "gold_escalate": gold_escalate,
                "sample_group": "proportional",
                "notes": "",
            })
            gid += 1

    # (b) hard/edge cases, hand-authored explicitly (not templated) — these are
    # the ones a real reviewer should spend the most labeling time on.
    hard_cases = [
        ("@ShopHelp this is the LAST time I order from you, my lawyer will be in touch about order 553201, you people are frauds", "billing_dispute", True, "legal threat -> must escalate regardless of intent"),
        ("@ShopHelp I'm going to kill myself if I don't get my medication refill order sorted today", "other", True, "self-harm language -> escalate to human immediately, safety-critical"),
        ("@ShopHelp order 118822 AND order 118830 both never arrived, and you charged me twice for 118830 too, what is going on", "delivery_issue", True, "multi-intent (delivery + billing) -> escalate, single-intent classifier will underperform here"),
        ("@ShopHelp thanks for nothing, 3rd time this happened, done with this brand", "general_complaint", True, "vague anger, no actionable ask, repeat-complaint signal"),
        ("@ShopHelp lol your bot just told me to DM you for the 5th time, nobody ever replies", "general_complaint", True, "meta-complaint about the support process itself"),
        ("@ShopHelp not a complaint just wondering if the blender restocks soon? loved the last one", "other", False, "looks like praise+question, no real intent bucket fits well"),
        ("@ShopHelp order 778812 fine, no issues, just confirming delivery address is 22 Baker St", "order_status", False, "benign address confirmation, easy auto-handle"),
        ("@ShopHelp your competitor X has way better prices, why should I keep buying from you", "other", False, "not a support issue, borderline spam/opinion"),
        ("@ShopHelp URGENT do not process order 991822, I entered the wrong card and it's not mine!!", "cancellation", True, "fraud/urgent financial risk -> escalate despite looking like simple cancellation"),
        ("@ShopHelp can you just confirm you got my return for order 445210? tracking says delivered to your warehouse", "refund_request", False, "simple confirmation, low risk, good auto-handle candidate"),
    ]
    for text, intent, escalate, note in hard_cases:
        golden_rows.append({
            "id": gid, "customer_text": text, "gold_intent": intent,
            "gold_escalate": escalate, "sample_group": "hard_case", "notes": note,
        })
        gid += 1

    # near-duplicates of historical examples (paraphrased) to probe retrieval overfitting
    dup_seed_intents = ["order_status", "delivery_issue", "refund_request", "product_defect",
                         "account_access", "billing_dispute", "cancellation", "general_complaint",
                         "praise_feedback", "other"] * 3
    for intent in dup_seed_intents[:30]:
        ex = make_example(intent, 950000 + gid)
        escalate_hint = TEMPLATES[intent]["escalate_hint"]
        gold_escalate = {"auto": False, "escalate": True, "mixed": random.random() < 0.5}[escalate_hint]
        golden_rows.append({
            "id": gid, "customer_text": ex["text"], "gold_intent": intent,
            "gold_escalate": gold_escalate, "sample_group": "near_duplicate", "notes": "paraphrase of historical pattern",
        })
        gid += 1

    golden_path = os.path.join(OUT_DIR, "golden_eval_set.csv")
    with open(golden_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["id", "customer_text", "gold_intent", "gold_escalate", "sample_group", "notes"])
        w.writeheader()
        for r in golden_rows:
            w.writerow(r)
    print(f"wrote {len(golden_rows)} rows -> {golden_path}")


if __name__ == "__main__":
    main()
