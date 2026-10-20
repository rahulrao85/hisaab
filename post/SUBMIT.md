# Hisaab: DEV submission checklist (Hacktoberfest Week 3)

**Deadline: 26-Oct-2026 12:29 IST** (25-Oct 11:59 PM PDT). Writing quality counts most in judging.
The repo must be created and finished within 19–25 Oct. It was started on 20-Oct-2026.

## Status

| # | Step | Who | Status |
|---|---|---|---|
| 1 | App built, 16 unit tests passing, APK has no INTERNET permission | Claude | Done 20-Oct |
| 2 | Accept the Gemma licence on Hugging Face and download `gemma3-1b-it-int4.task` to Downloads | **Govu** | |
| 3 | Push the model to the M35, benchmark CPU/GPU, check Gemma's merchant names on demo data | Claude | |
| 4 | Screenshots plus a 30–45 s screen recording of demo mode, recorded over ADB | Claude | |
| 5 | Upload the demo MP4 to YouTube as **unlisted**, then send me the link | **Govu** | |
| 6 | Real use: Setup → Grant → Scan last 90 days, then live with it for 2–3 days | **Govu** | |
| 7 | Tell me what it caught or saved, in one or two lines; this becomes "The Lazy Payoff" | **Govu** | |
| 8 | Approve creating the public repo `github.com/rahulrao85/hisaab` and pushing to it | **Govu** says yes, Claude pushes | |
| 9 | Fill every `REPLACE_` in `DEV_POST.md`, then make the cover image | Claude | |
| 10 | Publish on DEV (section 1 below) | **Govu** | |

## 1. Fields on the DEV "Create Post" page

Start from the template link on the challenge page (it pre-fills the tags), then:

| Field | Value |
|---|---|
| Cover image | `post/cover_2000x840.png` |
| Title | `Hisaab: Gemma reads my bank SMS on my phone, and the app has no internet permission` |
| Tags (max 4) | `devchallenge` `hf26challenge` `android` `privacy` |
| Body | everything in `post/DEV_POST.md` |

## 2. Before you click Publish

- [ ] Preview: the video, the three screenshots and the GitHub card all render.
- [ ] Ctrl+F `REPLACE_` finds nothing.
- [ ] The repo opens while logged out, so it's public.
- [ ] **AI disclosure**, at the bottom of the editor: the code and post were written with AI assistance (Claude Code).
- [ ] Leave **My Agent Session** out. This session covers private topics, so don't upload it to DevRelay. Also delete that heading from the body.
- [ ] Nothing real in any image: demo mode only (the yellow "DEMO DATA" chip is visible).

## 3. After publishing

- Share the post link (on GAL's community tab or LinkedIn). Reactions only break ties.
- Any commit after 26-Oct 12:29 IST must be noted in the README, or the entry risks disqualification.
- Winners are announced the week of 26-Oct.
