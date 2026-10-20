# Hisaab

**Your bank SMS, read on your phone by Gemma. Nothing leaves the device.**

*Hisaab* (हिसाब) is Hindi for "the accounts". In India every card swipe, UPI payment and autopay sends an SMS. Hisaab reads them for you, so you never have to open six banking apps to know what you spent:

- **Was this you?** An alert within a second of the SMS, with *It was me* / *Not me* buttons right on the notification, when:
  - a debit doesn't match the bill it pays (Airtel billed ₹1,199, ₹1,499 left your account)
  - the same charge lands twice within 10 minutes
  - you pay a merchant far more than usual (3× your median there)
  - you pay a new merchant more than ₹1,000
  - a charge is in a foreign currency, or comes in at 2 AM
  - a bill jumps more than 25% over the last one
  - a new auto-debit or subscription gets set up
- **Morning note at 08:00:** yesterday's spend, the month so far, bills due in the next 3 days, and anything still waiting for a yes or no.
- **Bills tab:** every bill SMS, matched to the debit that paid it.
- **CSV export** for your own spreadsheet.

Built 20–25 Oct 2026 for the DEV [Hacktoberfest Open-Source AI Challenge, Week 3: Slow and Steady](https://dev.to/challenges/hacktoberfest-week3-2026-10-19).

## Why on-device

Bank SMS are the most sensitive messages on a phone: amounts, account numbers, who you pay, when you're awake. Popular expense apps upload them to their servers to parse them. Hisaab can't, by construction:

- **The APK has no `INTERNET` permission.** The manifest removes it even if a library asks for it (`tools:node="remove"`), and the Setup screen checks this live. Verify it yourself: `aapt dump permissions app-debug.apk`.
- **The model is Gemma 3 1B** (int4, ~550 MB), run on the phone's CPU by Google's open-source MediaPipe LLM Inference runtime.
- **Only business senders are read.** Messages from `VM-HDFCBK`, `AX-ICICIT-S` and the like are processed; messages from people are skipped unread.

## How it works

```
SMS arrives ─► SmsParser (regex) ─► Rules ─► alert notification     (under a second, no model)
                    │
                    └─► EnrichWorker ─► Gemma 3 1B: "PYU*BUNDL TECHNOLOGIES" → Swiggy | Food
                                         (once per merchant, cached forever)
08:00 ─► Summary (code computes every number) ─► Gemma phrases it ─► guard: any number Gemma
                                                                     invented? use the plain version
```

**Who decides what:**

| Job | Done by | Why |
|---|---|---|
| Amounts, accounts, refs, due dates | Regex (`core/SmsParser.kt`) | Must be exact; a model must never guess money |
| When to alert | Rules (`core/Rules.kt`) | A 1B model shouldn't call anything fraud |
| Who the merchant really is, category | Gemma | Descriptors like `RAZ*DREAMPLUG` or `paytm-48213345@ptys` are where a language model earns its keep |
| The morning note's wording | Gemma, with a number guard | Any figure not in the computed facts → the note falls back to the template |

The core logic (`core/`) is plain Kotlin with no Android imports, so it's unit-tested on a laptop against 30 synthetic SMS covering HDFC, ICICI, SBI, Axis, Kotak and HSBC formats.

## Install

You need Android 10+ on an arm64 phone with about 1 GB free.

1. **Build:** `./gradlew :app:assembleDebug`
2. **Install:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. **Get the model.** Accept the Gemma licence at [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT), download `gemma3-1b-it-int4.task`, then push it to the phone:
   ```
   adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.govu.hisaab/files/
   ```
4. **Set up the app.** Open Hisaab → **Setup**:
   - **Grant** the SMS and notification permissions.
   - Tap **Scan last 90 days of SMS**, or switch on **Demo mode** to try it with made-up messages.

Alerts on SMS older than 7 days are marked as yours during the first scan, so the first run doesn't nag you about last month.

## Tests

```
./gradlew :app:testDebugUnitTest
```

## Limits

- It only knows what arrives by SMS. Bill PDFs and email statements are out of scope.
- The parser covers the common formats of the banks above. New formats fall through: unparsed means "no alert", never a wrong amount.
- It's sideloaded. Google Play restricts SMS-reading apps to default SMS handlers.

## Licence

The code is MIT licensed. Gemma is subject to the [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
