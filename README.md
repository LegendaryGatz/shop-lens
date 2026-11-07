# Shop Lens

Upload photos of a designer item (clothing, shoes, bags, jewelry, watches). Shop Lens returns the brand, designer, model, collection, condition and authenticity cues, a resale price range, and an estimated TheRealReal consignment payout.

Kotlin + Ktor backend and a single-page HTML UI.

## Run

```bash
brew install openjdk@21                     # once
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew run                               # http://localhost:8080
```

Keys are read from the environment (e.g. `~/.zshenv`) or a git-ignored `.env` file in the project:
`GEMINI_API_KEY` for identification, `TYPESAFE_API_KEY` for the Jev second opinion.

Tests: `./gradlew test`. They use fakes and make no API calls.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SHOPLENS_PROVIDER` | `gemini` if `GEMINI_API_KEY` is set, else `claude` | Which vision model provider to use |
| `SHOPLENS_GEMINI_MODEL` | `gemini-3.5-flash-lite` | Main identification model |
| `SHOPLENS_HEDGE_MODEL` | `gemini-3.5-flash` | Backup raced against the main model when it is slow or overloaded (`none` turns it off) |
| `SHOPLENS_HEDGE_AFTER_MS` | `8000` | How long to wait before sending the backup request |
| `SHOPLENS_ESCALATION_MODEL` | unset | Stronger model (e.g. `gemini-4-argon`) to re-identify when Jev disagrees. Needs a paid Gemini tier |
| `SHOPLENS_EFFORT` | `low` | Thinking level |
| `TYPESAFE_API_KEY` | unset | Enables the Jev second opinion |
| `SHOPLENS_AUTO_VERIFY_BELOW` | `0` (off) | Automatically verify when model confidence is below this |
| `GOOGLE_VISION_API_KEY` | unset | Enables reverse-image search during verification |
| `ANTHROPIC_API_KEY`, `SHOPLENS_MODEL` | unset, `claude-opus-5-5` | Claude provider (`SHOPLENS_PROVIDER=claude`) |
| `PORT` | `8080` | |

**Gemini free tier:** identification with `gemini-3.5-flash` and `-flash-lite` works.
Gemini 4 Argon and Google Search grounding (the "Verify" checkbox) return quota errors until you
enable billing in AI Studio. Google may also use free-tier inputs to improve its products, so
don't upload photos you'd consider private.

## How it works

```
photos ─► resize to ≤1568px JPEG (browser and server; JPEG/PNG/WebP/HEIC-in-Safari)
       ─► Gemini Flash-Lite vision call (structured JSON) ── slow or 503? race full Flash after 8s
       ─► Jev second opinion: P(brand), P(model), category, label visible, photo quality
              └─ disagrees and SHOPLENS_ESCALATION_MODEL set ─► re-identify with the stronger model
       ─► [optional "Verify"] Google Search-grounded check: product name, season, comps
       ─► TheRealReal commission table ─► payout range
```

- **Gemini** writes the open-ended identification: brand, designer, model, season, text on labels, condition, price.
- **Jev** (TypeSafe System One) can't write descriptions, but returns calibrated probabilities on narrow
  questions for about $0.0002 per check. The verdict thresholds live in `JevChecker.interpret`, so they can
  be tuned against real outcomes. Jev's image input is a beta feature in TypeSafe's docs.
- Both providers implement `ItemIdentifier`; Claude remains available as an alternative.

**Measured on one test photo (a Prada dress, free tier, Nov 6 2026):**
- With full Flash as the main model, every request ended up answered by the Flash-Lite backup, in 11–14s.
  Full Flash was overloaded or slow each time; one bare call took 92s.
- With Flash-Lite as the main model, each request took 2.6–6.5s end to end.
- Every run said Prada at 95% brand confidence.
- Jev put the brand at 52–61%. That's reasonable given it judged a legible label only 19% likely to be visible.

## Research summary (Nov 2026)

Three parallel research agents produced these findings. Treat them as directional.

**Models.** There is no published luxury-specific benchmark. On Roboflow's general vision evals:

- Claude Opus 5.5 is the fastest of the top tier (about 7.6 s per sample, about $0.015).
- GPT-6.1 Terra and Gemini 4 Argon score slightly higher overall and cost less per sample. Gemini's prices double on Dec 1.
- Haiku-class models are about 100× cheaper but trail by 10–20 points on identification and OCR. They suit triage, not the final ID.

The next step is a labeled eval of about 200 items, which would settle which model and effort to use.

**Visual search.** Bing Visual Search was retired in Aug 2025.

- Google Cloud Vision Web Detection ($3.50 per 1k calls) is the option that doesn't break anyone's terms of service.
- Google Lens through SerpApi has better recall (about 2.5¢ per call) but scrapes Google against its terms.
- Fashion APIs such as Syte, Vue.ai, Ximilar and Lykdat only search a retailer's own catalog.
- The long-term win is a self-hosted SigLIP/CLIP index of confirmed items, at fractions of a cent per lookup.

**Pricing data.**

- TheRealReal, Vestiaire, Fashionphile, Rebag and Grailed have no public APIs.
- Scraping TheRealReal likely violates its terms.
- eBay's sold-prices API (Marketplace Insights) is gated. The Browse API is open but returns active listings only.
- WatchCharts has a paid API for watches.

The MVP therefore uses a model-estimated range checked against web comps. Expect roughly ±15–25% on staple models and much worse on generic clothing.

**Commission table.** The table in `Payout.kt` comes from a third-party copy of TheRealReal's Earnings Guide (Oct 2026), because TheRealReal blocks automated fetches. Verify it against therealreal.com/seller/commissions.

## Next steps toward "what is my closet worth"

1. Build an eval set of 200–500 photographed items with known IDs and sale prices. Use it to choose the model, effort and image size.
2. Cache results per image hash, and log what users confirm or correct.
3. Add eBay Browse API comps, WatchCharts for watches, and a payout comparison across TheRealReal, eBay and Rebag/Fashionphile instant offers.
4. Add a mobile camera capture flow, plus a closet view that saves items and totals their value.
