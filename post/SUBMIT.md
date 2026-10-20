# Hisaab: DEV submission checklist (Hacktoberfest Week 3)

**Deadline: 26-Oct-2026 12:29 IST** (25-Oct 11:59 PM PDT). Writing quality counts most in judging.
The repo must be created and finished within 19–25 Oct. It was started on 20-Oct-2026.

## Status

| # | Step | Who | Status |
|---|---|---|---|
| 1 | App built, 16 unit tests passing, APK has no INTERNET permission | Claude | Done 20-Oct |
| 2 | Gemma models: 3 1B (licence accepted) and 4 E2B (Apache-2.0) downloaded and copied to the M35 | Govu + Claude | Done 20-Oct |
| 3 | Benchmarks and the 24-merchant accuracy check on the M35 (results are in the post and README) | Claude | Done 20-Oct |
| 4 | Screenshots, cover, and a 66 s demo video (`video/renders/hisaab_demo.mp4`) | Claude | Done 20-Oct |
| 5 | Upload `hisaab_demo.mp4` to YouTube as **unlisted**, ticking "altered or synthetic content" (cloned voice), then send me the link | **Govu** | |
| 6 | Real use: Setup → Grant → Scan last 90 days, then live with it for 2–3 days | **Govu** | |
| 7 | Tell me what it caught or saved, in one or two lines; this becomes "The Lazy Payoff" | **Govu** | |
| 8 | Approve creating the public repo `github.com/rahulrao85/hisaab` and pushing to it | **Govu** says yes, Claude pushes | |
| 9 | Fill the last two `REPLACE_`s in `DEV_POST.md` (the video link and the Lazy Payoff) | Claude | Cover done |
| 10 | Publish on DEV (section 1 below) | **Govu** | |

## 1. Fields on the DEV "Create Post" page

Start from the template link on the challenge page (it pre-fills the tags), then:

| Field | Value |
|---|---|
| Cover image | `post/cover_2000x840.png` |
| Title | `Hisaab: Gemma 4 reads my bank SMS on my phone, and the app has no internet permission` |
| Tags (max 4) | `devchallenge` `hf26challenge` `android` `privacy` |
| Body | everything in `post/DEV_POST.md` |

## 2. Before you click Publish

- [ ] Preview: the video, the three screenshots and the GitHub card all render.
- [ ] Ctrl+F `REPLACE_` finds nothing.
- [ ] The repo opens while logged out, so it's public.
- [ ] **AI disclosure**, at the bottom of the editor: the code and post were written with AI assistance (Claude Code), and the video narration is an AI clone of my voice (ElevenLabs).
- [ ] Leave **My Agent Session** out. This session covers private topics, so don't upload it to DevRelay. Also delete that heading from the body.
- [ ] Nothing real in any image: demo mode only (the yellow "DEMO DATA" chip is visible).

## 3. After publishing

- Share the post link (on GAL's community tab or LinkedIn). Reactions only break ties.
- Any commit after 26-Oct 12:29 IST must be noted in the README, or the entry risks disqualification.
- Winners are announced the week of 26-Oct.
