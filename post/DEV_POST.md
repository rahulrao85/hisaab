*This is a submission for the [Hacktoberfest Open-Source AI Challenge Week 3: Slow and Steady](https://dev.to/challenges/hacktoberfest-week3-2026-10-19)*

## What I Built

In India, money talks by SMS. Every card swipe, every UPI payment, every autopay sends a text. I have accounts at more banks than I'd like to admit, so my inbox gets dozens of these a week. I read almost none of them.

That's a problem, because the SMS is often the only place a mistake shows up. A bill that says ₹1,199 but debits ₹1,499. An Uber ride charged twice, three minutes apart. A dollar subscription billed at 2 AM that I forgot I'd signed up for. Each one is right there in my inbox, unread.

The usual fix is an expense app that "reads your SMS". The catch is how it reads them: most upload your messages to their servers and parse them there. Your bank SMS are some of the most private data on your phone: what you pay, who you pay, when you're awake, and the last digits of every account and card.

So I built **Hisaab** (हिसाब, Hindi for "the accounts"). It's an Android app that reads my bank SMS with **Gemma 3 1B running on the phone itself**, and it can't send anything anywhere: **the app has no internet permission at all.**

What it does, so I don't have to:

- **"Was this you?" alerts, within a second of the SMS.** It flags:
  - a debit that doesn't match its bill
  - a double charge
  - a payment far above my usual at that merchant
  - a large first payment to someone new
  - a foreign-currency or 2 AM charge
  - a bill that jumped from last time
  - a new auto-debit I never set up

  *It was me* and *Not me* buttons sit right on the notification.
- **A morning note at 08:00:** yesterday in two sentences, the month so far, and bills due soon.
- **Bills, matched:** every bill SMS is paired with the debit that paid it, so "did I pay Tata Power?" takes a glance, not a search.

## Demo

REPLACE_DEMO_VIDEO

| Was this you? | Bills, matched | No internet, by design |
|---|---|---|
| ![Alerts](REPLACE_IMG_ALERTS) | ![Bills](REPLACE_IMG_BILLS) | ![Setup](REPLACE_IMG_SETUP) |

*The screenshots use the app's demo mode: 30 made-up SMS in a separate database. No real account appears anywhere in this post.*

## Code

{% embed https://github.com/rahulrao85/hisaab %}

## How I Built It

**The rule I started with: the model never touches money.** A 1B model is small, and small models guess. So I split the work by how wrong each part is allowed to be:

| Job | Who does it | Why |
|---|---|---|
| Amount, account, reference number, due date | Plain regex | Must be exact. An unparsed SMS means "no alert", never a wrong ₹ figure |
| When to alert | Plain rules | A phone-sized model shouldn't be the one calling something fraud |
| Who the merchant really is, and its category | **Gemma 3 1B, on the phone** | This is where a language model earns its place |
| The wording of the morning note | **Gemma 3 1B**, behind a number guard | Friendly text, but only with numbers the code computed |

**Why Gemma is needed at all.** Bank SMS don't say "Swiggy". They say `PYU*BUNDL TECHNOLOGIES`, `RAZ*DREAMPLUG` or `paytm-48213345@ptys`. A lookup table can't keep up with payment gateways, UPI handles and abbreviations, but a language model that has read the internet knows that Bundl Technologies is Swiggy's company. Gemma gets a short few-shot prompt and answers in one line, `Name | Category`. Each merchant is asked about **once**, and the answer is cached forever. So the model runs a handful of times a week, not on every SMS. That's the slow-and-steady part: it does very little, and only when there's something new to learn.

**Running it on the phone.** Google publishes Gemma 3 1B as a 4-bit `.task` file (about 550 MB) for its open-source **MediaPipe LLM Inference** runtime. I load it on the CPU, with greedy decoding (temperature 0) so the same SMS always gets the same answer. On my Galaxy M35, a mid-range phone with an Exynos 1380:

| | Result |
|---|---|
| Model load | REPLACE_LOAD |
| One merchant label (prompt + answer) | REPLACE_LABEL |
| Morning note | REPLACE_NOTE |
| RAM while running | REPLACE_RAM |

All of it runs in a background job, so I never wait on it. The alert itself doesn't use the model at all, so it fires the moment the SMS lands.

**The guard that keeps Gemma honest.** In my first test, the morning note came back REPLACE_GUARD_STORY. So the code now pulls every number out of Gemma's note and checks each one against the facts it was given. If even one figure isn't in that list, Hisaab throws the note away and shows a plain template instead. You might get a slightly duller note, but you will never get a made-up ₹ amount.

**Parsing Indian bank SMS.** Every bank has its own format, and a few of them nearly broke the parser:

- ICICI writes *"Acct XX107 debited for Rs 1,199.00; AIRTEL credited"*. Both "debited" and "credited" appear in a single debit. Whichever verb comes first decides.
- SBI writes *"debited by 4999.0"*, with no ₹ sign at all.
- Airtel's bill SMS says "Post**paid**", which my first parser read as "paid", turning a bill into a payment. Word boundaries fixed it.
- Card statements carry two amounts, total due and minimum due. Hisaab takes the total.
- Billers send *"Payment of ₹1,499 has been received, thank you"*. These are skipped, because the bank's own debit SMS already counts that money.

All of this sits in plain Kotlin with no Android code, so it's unit-tested on a laptop. 16 tests run against 30 synthetic SMS covering HDFC, ICICI, SBI, Axis, Kotak and HSBC.

**The first scan.** When you install it, Hisaab reads your last 90 days of SMS. The first version alerted on every "new merchant" from three months ago, and nobody needs that. Now anything older than 7 days is quietly marked as yours, and only recent surprises ask for an answer.

## Why Does Open Innovation Matter?

For this app, the open model is the whole idea.

**A closed API would need the internet, and this app has none.** To use a hosted model, Hisaab would have to send my bank SMS to someone else's server, which is exactly the problem with existing expense apps. With Gemma's open weights running on the phone, I could remove the `INTERNET` permission entirely. That's a claim you can check: the Setup screen reads the app's own manifest and shows it, and `aapt dump permissions` on the APK shows the same. "Private by policy" asks you to trust a company. "Private because it physically can't connect" doesn't ask you to trust anyone.

**It costs nothing to run, forever.** There's no API key, no per-call price and no subscription. That matters because the app is meant to run every day for years. Even a cheap API charges on every call, and that adds up over years of daily use.

**It works offline.** On a flight, in a basement, or in airplane mode, the alerts and merchant labels still work.

**I can change how it behaves.** The prompt, the categories and the guard are all mine to change. The model file can be swapped for a bigger Gemma when phones get faster. MediaPipe is open source too, so when something misbehaved I could read the code instead of guessing.

## The Lazy Payoff

REPLACE_LAZY_PAYOFF

## Prize Categories

**Best Use of Gemma:** Gemma 3 1B runs on the phone through MediaPipe, with no server at any step.
