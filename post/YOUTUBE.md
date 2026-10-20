# YouTube upload: Hisaab demo

**File:** `video/renders/hisaab_demo.mp4` (66 s, 1080p)
**Channel:** @rahulrao85, the same place as the Zubaan demos. Not GAL.

## Settings
- **Visibility: Unlisted**
- Altered or synthetic content: **Yes** (the narration is an AI clone of my voice)
- Audience: **No, it's not made for kids**
- Category: Science & Technology
- Thumbnail: `post/cover_2000x840.png` works, or use YouTube's auto frame at 0:22

## Title
Hisaab: Gemma 4 reads my bank SMS on my phone (no internet permission)

## Description
Every rupee I spend sends me an SMS, and I read none of them. Hisaab reads them for me, on the phone itself.

• "Was this you?" alerts, within a second of the SMS: a bill that doesn't match its debit, a double charge, a first payment to a new merchant, a foreign or 2 AM charge, a bill that jumped, a new autopay
• Every bill matched to the payment that settled it
• One calm note each morning about yesterday

How it works: plain rules handle amounts and alerts, so money is never guessed. Gemma 4 E2B, Google's open model, runs on the phone through LiteRT-LM and turns cryptic payee codes into names. The app has no INTERNET permission, so it cannot send your messages anywhere.

Measured on a Galaxy M35 (CPU): 2.6 s per new merchant, 18/24 categories right on a 24-merchant check.

Code (MIT): https://github.com/rahulrao85/hisaab
Write-up on DEV: (add the post link after publishing)

Built for the DEV Hacktoberfest 2026 Open-Source AI Challenge, Week 3: Slow and Steady.
Demo mode only: every SMS on screen is made up. The narration is an AI clone of my own voice (ElevenLabs).

0:00 Every rupee sends an SMS
0:08 Where the mistakes hide
0:20 Hisaab on the phone
0:37 How it works
0:49 No internet permission
0:58 Open model, free forever

## Tags
gemma, gemma 4, on-device ai, android, litert, open source ai, hacktoberfest, dev challenge, bank sms, upi, expense tracker, privacy, offline ai
