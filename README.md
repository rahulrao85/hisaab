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
- **The model is Gemma 4 E2B** (`.litertlm`, 2.6 GB, Apache-2.0), run on the phone's CPU by Google's open-source LiteRT-LM runtime. Gemma 3 1B (`.task`, 550 MB, MediaPipe) also works on smaller phones; pick it in Setup.
- **Only business senders are read.** Messages from `VM-HDFCBK`, `AX-ICICIT-S` and the like are processed; messages from people are skipped unread.

## How it works

```
SMS arrives ─► SmsParser (regex) ─► Rules ─► alert notification     (under a second, no model)
                    │
                    └─► EnrichWorker ─► Gemma 4 E2B: "HPCL MAHALAXMI SERVICE STN" → HPCL | Fuel
                                         (once per merchant, cached forever)
08:00 ─► Summary (code computes every number) + one Gemma line that may not contain any figure
```

**Who decides what:**

| Job | Done by | Why |
|---|---|---|
| Amounts, accounts, refs, due dates | Regex (`core/SmsParser.kt`) | Must be exact; a model must never guess money |
| When to alert | Rules (`core/Rules.kt`) | A 1B model shouldn't call anything fraud |
| Who the merchant really is, category | Gemma | Descriptors like `RAZ*DREAMPLUG` or `paytm-48213345@ptys` are where a language model earns its keep |
| One friendly line in the morning note | Gemma, no figures allowed | A digit or ₹ in its line → the line is dropped; the numbers always come from code |

The core logic (`core/`) is plain Kotlin with no Android imports, so it's unit-tested on a laptop against 30 synthetic SMS covering HDFC, ICICI, SBI, Axis, Kotak and HSBC formats.

## Install

You need Android 10+ on an arm64 phone with about 3 GB free (1 GB for the small model).

1. **Build:** `./gradlew :app:assembleDebug`
2. **Install:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. **Get the model.** Download `gemma-4-E2B-it.litertlm` from [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (Apache-2.0, no sign-up), then push it to the phone:
   ```
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.govu.hisaab/files/
   ```
   For a phone with little free space, use Gemma 3 1B instead: accept the licence at [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT) and push `gemma3-1b-it-int4.task` the same way.
4. **Set up the app.** Open Hisaab → **Setup**:
   - **Grant** the SMS and notification permissions.
   - Tap **Scan last 90 days of SMS**, or switch on **Demo mode** to try it with made-up messages.

Alerts on SMS older than 7 days are marked as yours during the first scan, so the first run doesn't nag you about last month.

## Accuracy check (on the phone)

Setup → **Accuracy check** runs 24 Indian payee strings that never appear in the prompt (`core/EvalSet.kt`). On a Galaxy M35, CPU:

| | Gemma 3 1B | Gemma 4 E2B |
|---|---|---|
| Names right | 15 / 24 | 16 / 24 |
| Categories right | 9 / 24 | 18 / 24 |
| Time per merchant | 4.5 s | 2.6 s |

Misses are legal names that hide the brand (Kirana Kart = Zepto, Dreamplug = CRED). Tap a transaction to rename it once; every payment to it follows.

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
