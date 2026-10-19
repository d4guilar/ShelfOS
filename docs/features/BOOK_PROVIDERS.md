# Books Metadata & Cover Providers — Multi-Provider Plan (Phase 4.5C.0)

Status (2026-10-16): planning accepted in 4.5C.0 (PR #47, ADR-0026 Accepted). **4.5C.1 infrastructure
(registry, policy engine, clustering and ranking, fakes only) is merged (PR #48)**; see plan §36. 4.5C.2
and later are not implemented and Open Library D1/D31 remain unresolved. No network call, `INTERNET`
permission, manifest change, dependency or Room change exists or is approved by this document.
Decision record: [ADR-0026](../adr/0026-multi-provider-books-metadata.md).
Supersedes the single-provider "Open Library primary" framing of
[`PHASE_4_5_IMPLEMENTATION_PLAN.md`](../PHASE_4_5_IMPLEMENTATION_PLAN.md) §11; every earlier
provider rejection and authorization decision (§14, D1, D2, D5–D7) is **preserved unchanged**.

## 1. Evidence conventions

- **CONFIRMED** — read on the provider's official page on 2026-10-16 (or 2026-10-15 for rows
  carried over from plan §14).
- **UNCONFIRMED** — official page unreadable (403/404) or silent; figure comes from a search
  summary, a third party or general knowledge. Must be re-verified at that provider's slice
  start. Never a basis for approval.
- Open source does not make ShelfOS "noncommercial". ShelfOS is free-core but has paid cosmetic
  themes and accepts donations; every provider is judged as a **commercial-model distributed
  app**. A public endpoint is not a redistribution licence. None of this is legal advice.

## 2. Provider comparison

| Provider | Kind | Covers | Auth | Limits | Cache / storage | Commercial-model fit | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Open Library** | Independent catalog (Internet Archive), editions + works | Yes (S/M/L; cover-id/OLID unlimited, ISBN/OCLC/LCCN 100 per IP per 5 min) | None; `User-Agent` with app + contact; 3x limit when identified | 1 req/s anonymous, 3 req/s identified. CONFIRMED | "Cache responses whenever possible." Covers API "intended for displaying covers on public facing websites and not for bulk download"; "do not crawl". CONFIRMED | API "not meant for high-traffic commercial infrastructure"; wants "useful, time-sensitive requests on behalf of human users"; no hundreds of single-book requests; violations may be rate limited/blocked. Distributed-client use **not** stated as approved | **CONDITIONAL — D1 unchanged** (not approved) |
| **Library of Congress** (loc.gov JSON/YAML API; catalog SRU is a separate service, §3.11) | Independent national catalog, bibliographic | No book-cover service found; image services are for digitized collections | None (no key) | JSON/YAML API **20 req/min, 1 h block**; MARCXML 30/min, **72 h block**; 429 and HTML CAPTCHA possible below limits. CONFIRMED | Not stated on the limits page | Limits page has no terms/attribution; "Legal" page **not read**. Catalog moved to a new platform 2025-06-30 and API data is "not fully synced" — ISBN coverage may be incomplete. CONFIRMED | **CONDITIONAL, metadata-only secondary** — needs its terms read (D22) |
| **Gutendex** | Third-party wrapper of Project Gutenberg metadata (public domain only) | No cover field documented, but the live `formats` map carries an `image/jpeg` entry (§3.12) | None | Not documented | Not documented. Docs: "for long-term use, please run your own server" | No terms published; hobby-hosted. Scope limited to public-domain texts | **REJECTED as a client dependency** (public instance); out of scope for Books of the user's own files |
| **Hardcover** | Community book platform, GraphQL | Yes | Personal account token, "not meant to be shared"; may be reset "without notice" in beta | Official page 403 in this pass; 429 documented; "60/min" is third-party, UNCONFIRMED | Not reviewed | Docs say you can't use the API to access/use someone else's data; ownership rules match site content; third party reports "same licensing as OpenLibrary" — UNCONFIRMED. Beta | **RESEARCH → BYOK-only candidate** (D25); never default |
| **OverDrive** | Commercial library-lending platform | Yes (Discovery API) | Vendor/partner request form; OverDrive issues client key **and secret** | Not read | Terms not read | Approval is manual; Libby/Sora branding required when titles shown; secret cannot ship in an open-source APK; reflects library collections, not an independent catalog | **REJECTED for default** (D26) |
| **Google Books** | Commercial catalog | Thumbnails | Key required for public data (CONFIRMED); ToS §4(b)(1) bars credentials in open-source projects (read 2026-10-15) | Per-key | §5(e) no permanent copies (read 2026-10-15) | "You may not charge users any fee for the use of your application" (CONFIRMED again 2026-10-16) — ShelfOS Plus conflicts | **REJECTED — D2 unchanged.** No new evidence to reopen |
| **w3slley/bookcover-api** | **Wrapper**: fetches covers from **Goodreads** (README) | Yes, via Goodreads | None | None stated; 429 on quota | None stated | Repo is MIT, but MIT covers the wrapper's code, not Goodreads' content; no disclaimer on image rights; public instance is a single-person host. Goodreads is not an independent catalog | **REJECTED** (D27) |
| Other independents (not read this pass) | Internet Archive (same operator/terms family as Open Library), Wikidata/Wikimedia Commons (open data; covers mostly non-free), national libraries (DNB, BnF, etc.), Standard Ebooks (public domain only) | Varies | Varies | UNCONFIRMED | UNCONFIRMED | UNCONFIRMED | **RESEARCH**; none proposed for 4.5C |

Honest summary: the only provider that is **keyless, independent, edition-level and cover-bearing**
is Open Library, and it is still unapproved. Library of Congress can add metadata
cross-checking only. Everything cover-bearing beyond Open Library is keyed (BYOK), a wrapper,
or rejected. A multi-provider architecture is still the right shape (ADR-0026), but 4.5C
will realistically ship with **one** approved provider; others plug in by registry entry.

## 3. Architecture proposal

Packages stay inside the existing boundaries (`core.network`, `data.metadata`, `domain.library`).
The 4.5B `MetadataProvider` / `MetadataCandidate` / `MetadataQuery` / `MetadataMatcher` /
`MetadataEnrichmentService` contracts are extended, not replaced. UI never imports a provider.

### 3.1 Provider registry and enablement

`ProviderRegistry` holds `ProviderDescriptor` entries compiled into the app:

```
id, displayName, capabilities {ISBN_LOOKUP, SEARCH, METADATA, COVERS},
categories {BOOK, MANGA, COMIC}, credential {NONE, USER_TOKEN},
termsStatus {APPROVED, CONDITIONAL, REJECTED}, termsCheckedOn,
rateLimit {perSecond | perMinute, burst}, maxConcurrency, backoff,
attribution (string resource ids + url), privacyDisclosure (string resource id)
```

A provider is **active** only if all hold: `termsStatus == APPROVED` (build-time, set only by an
approved decision row), the global online toggle is on, the provider's own toggle is on, and
(for `USER_TOKEN`) the user has entered a token. `CONDITIONAL`/`REJECTED` descriptors are not
registered in release builds. A terms change disables a provider by flag without touching
cached user data (plan §14.5). Zero active providers is a valid, fully working state.

### 3.2 Lookup order

1. Local evidence first (4.5B). 2. **ISBN-13 exact lookup** against each active provider that
has `ISBN_LOOKUP`. 3. Only if no ISBN: bounded title/creator search (one provider at a time,
`fields=`-limited, result cap). 4. Cover fetch only for a chosen edition. Lookup is **lazy and
per item** (on import commit, on explicit refresh, or when the user opens Find cover); never a
full-library burst, never on screen open (plan §30).

### 3.3 Rate limits, concurrency, backoff

Per-provider token bucket from the descriptor (Open Library ≤ 3 req/s identified; LoC ≤ 20/min,
set to ≤ 10/min for margin). Global concurrency 2, per-provider concurrency 1. Honor
`Retry-After`; exponential backoff with jitter; circuit breaker opens after N consecutive
failures and stays open for a cool-down; LoC treats HTML/CAPTCHA/429 as an immediate breaker
trip. Timeouts and response-size caps per the 4.5B network contract. No retry loop survives
process death without a persisted next-attempt time (no WorkManager, D13).

### 3.4 Offline-first caching

Each normalized response is cached (keyed by provider + request identity + schema version) with
a TTL; negative results get a shorter TTL; offline serves cache and never errors the UI. Selected
or auto-applied artwork is stored once as a bounded file in the existing `ONLINE` cover slot
(plan D21; ≈ 512 KiB, ≤ 1200 px, D12/D20 policies). **No hotlinking, no Google-style "short TTL
only" caches.** Provider data and covers stay out of exports/backups (plan §14 item 6 / D19).
Any new table or column is a separate Room slice, version chosen then (D16).

### 3.5 Cross-provider duplicate detection and edition-aware matching

Candidates carry `(providerId, providerRef, isbn13s[], title, creators, publisher, year, language,
format, pages, volume)`. Clustering: (1) group by shared normalized ISBN-13 (ISBN-10 converted);
(2) merge clusters that share a provider-neutral identifier (LCCN/OCLC) *and* agree on
title+creator; (3) never merge across ISBNs by title alone — different editions stay separate
**edition candidates**, grouped under a work only for display. Existing `MetadataMatcher` tiers
(plan §15) apply per cluster; independent providers agreeing on the same ISBN **ranks and corroborates** candidates
(ordering and a tie-break signal), but **never bypasses** the 4.5B edition, identifier and
contradiction rules: tier assignment, hard rejections, language/volume contradictions and the
"title alone is never VERY_HIGH" safety rules stay exactly as in plan §15. Agreement cannot
lift a candidate the matcher would cap, and disagreement still demotes both (two disagreeing
VERY_HIGH candidates demote). Cover duplicates collapse by
identical bytes/URL, then by perceptual hash of the thumbnails already downloaded for the picker
(no extra downloads for hashing).

### 3.6 Provenance

Every field written from a provider records `providerId`, `providerRef` (edition key),
`matchTier`, `retrievedAt`, and the cluster's corroborating provider ids. `USER` always wins
(AGENTS rule 8); provider data fills only unset/non-USER fields; "Not this publication" rejects
a candidate and reverts non-USER fields it supplied (plan §16).

### 3.7 Cover size and quality rules

Candidate must: decode to a valid bitmap; not be a provider placeholder (known blank sizes,
1×1, near-uniform image, `?default=false` / 404 handling for Open Library, ids ≤ 0 dropped per
plan §15); respect the 40 Mpx decode cap and bounded storage. **Automatic apply uses the
accepted plan §17 "high quality" gate unchanged**: at least the existing cover's pixel area or
a provider-large size (proposed ≥ 600 px long edge), correct orientation (portrait unless the
local art is landscape), VERY_HIGH match. In the **manual picker**, smaller images remain
selectable but are flagged "low resolution". Prefer the largest provider size that fits the
storage policy; thumbnails (`-M`/small) for the picker, full size only on select/auto-apply.
Previous artwork is kept until the new file is fully downloaded and decoded.

### 3.8 Automatic enrichment vs manual selection

- **Automatic**: only VERY_HIGH (exact ISBN-13 + no contradiction; provider agreement may
  rank/corroborate but does not change the tier); fills unset non-USER text fields.
- **Cover precedence is the accepted plan §17 order, restored:**
  `USER > confident high-quality ONLINE > usable embedded/LOCAL > generated`. For **Books**, a
  VERY_HIGH online cover passing the §3.7 gate may therefore replace a usable embedded cover
  (e.g. an EPUB cover) automatically; this is not limited to items without a local cover.
- **Comics and Manga keep D7**: an online cover does not replace an embedded CBZ/CBR
  first-page/ComicInfo cover automatically (it also requires the exact issue/volume match, and
  the default is off); replacement there is manual-picker only unless the administrator
  changes D7. Ambiguous remote artwork never replaces good local artwork.
- **Manual**: everything else (MEDIUM, search-only, series images, cross-provider disagreement,
  replacing a real local cover). Candidates are shown, never applied silently.
- A user-selected cover (USER slot) is never replaced by any automatic path.

### 3.9 Attribution and privacy

Per-provider attribution in Settings → About and the details technical section (descriptor
strings). Settings → Online metadata lists each provider with: what is sent (ISBN or
title/creator query, the device's IP address, the app `User-Agent`), what is not (no files,
no notes, no account), the provider's name/link, and its toggle. One-time non-blocking disclosure
(D15). `USER_TOKEN` providers store the token locally, never log it, never export it.

### 3.10 Failure isolation

Provider errors map to a closed `ProviderFailure` set (offline, timeout, rate-limited, blocked,
malformed, terms-disabled). Failures are recorded per provider/item with retry-after, never
thrown into import, library list or reader, and never convert to a permanent `NONE` (plan
§23.3). Books, Comics and Manga share the same pipeline; per-category matching rules (plan
§12–13) are unchanged, and Comics remain gated on the Metron path (D5/D19).

### 3.11 Library of Congress access paths

Two different services exist and must not be conflated. (a) The **loc.gov JSON/YAML API**
(loc.gov/apis; limits in §2) serves LoC *collections* data built for the loc.gov website; the
page does not mention ISBN search. (b) The **catalog** (catalog.loc.gov) with "Search/Retrieval
via URL" (SRU)/Z39.50 is listed by LoC as an additional service "maintained by the Library
separately from the loc.gov API"; its protocol, current status, limits and terms were **not
verified** (the apis page does not name SRU or give dates). Which path is the right ISBN
lookup for D22 is unresolved: 4.5C.4 must identify the supported ISBN path, read its
limits/terms and prove coverage with ISBN fixtures before any approval.

### 3.12 Gutendex image/jpeg availability

The Gutendex docs define `formats` as a MIME-type→URL map and name no cover field. A live query
(2026-10-16, *Pride and Prejudice*, id 1342) returned an `image/jpeg` entry pointing to
`gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg`, a Project Gutenberg generated cover.
This is **observed, not documented**: presence may vary per book and it is not necessarily the
publisher's edition cover. It does not change D24 (no published terms or limits; the public
instance asks for self-hosting; public-domain texts only) and is not a basis for an ISBN
edition cover.

## 4. Unified Find Cover experience (Phase 4.5F design)

Entry points: details pane "Find cover…", editor Cover section, and the "needs review" item
state. Opening it triggers the (lazy) lookup; nothing runs before.

Layout (adaptive; two panes on expanded width, single list on compact; works with D-pad):

1. **Fixed sources row, always first**: *Current cover*, *File cover* (if one exists),
   *Generated*, *Choose from device…*. These work offline and with all providers disabled.
2. **Online candidates grid** under a heading with a per-provider status line
   ("Open Library · 6 results", "Library of Congress · unavailable — Retry"). Each tile shows
   the cover in the user's chosen display shape, a **source chip** (provider name), and caption
   *publisher · year · format · language*. Tiles whose edition matches the item's ISBN are
   labelled "Same ISBN"; others "Other edition"; series-level images (if ever allowed) "Series
   image". Low-resolution tiles say so; cross-provider duplicates appear once with all source
   chips.
3. Selecting a tile opens a preview (full size, dimensions, source, rights note) with **Use
   this cover** (downloads once → USER slot, confirmed durable) and **Back**. "Use file cover"
   and "Revert" always remain available.
4. States: offline ("Showing saved results"), all providers off (link to Settings), rate
   limited (retry-after shown), no results (offer device picker), per-provider failure never
   hides other providers' results.
5. Accessibility/input: semantic commands (select, back, retry), focus restored after
   recreation, grid focus preserved, scroll/selection survive rotation/fold, localized strings
   only (EN/ES/PT-BR), TalkBack labels include source and edition.

**Minimal picker earlier in 4.5C — assessment: recommended, as its own last 4.5C slice
(4.5C.5), droppable to 4.5F without blocking 4.5C.** Reasons: 4.5C persists MEDIUM suggestions
and auto-applies covers, so without any chooser a wrong or missing online cover has no remedy;
"Not this publication" and the USER-slot writer are needed for safe auto-apply to be shippable.
Scope if included: single-column list, edition caption + source chip, one tile action *Use
this cover*, fixed sources row without *Choose from device* (that and the grid/preview polish
stay 4.5F). Cost: pulls the USER-slot write path and picker accessibility into 4.5C (D28).

## 5. Confirmed / conditional / rejected

- **Confirmed (official pages):** Open Library limits, `User-Agent` identification, "cache
  responses whenever possible", no crawl/bulk, "not high-traffic commercial infrastructure",
  covers API intent and per-IP ISBN limit; LoC rate-limit table and CAPTCHA/429 warning and
  no-key access; Google Books fee restriction and key requirement; OverDrive manual approval
  with client key + secret, Libby/Sora branding; Gutendex has no documented auth/limits/terms/
  cover field and recommends self-hosting; bookcover-api fetches from Goodreads (MIT code).
- **Conditional:** Open Library (D1), LoC metadata-only (D22), Hardcover BYOK (D25), Metron
  (D5/D19, unchanged).
- **Rejected:** Google Books (D2), OverDrive default (D26), Gutendex public instance (D24),
  bookcover-api / Goodreads (D27), Comic Vine/MangaDex (unchanged).
- **Not confirmed in this pass:** LoC "Legal"/reuse terms; Hardcover official API rules and
  limit; OverDrive terms text; Goodreads' current ToS and API status (stated from general
  knowledge only); every "other independent" row.

## 6. Slice plan and approval gates

*Implementation note (4.5C.1):* search runs only for an item without an ISBN (§3.2 as written);
the 4.5B coordinator's "search after an ISBN miss" was removed.


| Slice | Content | Gate before start |
| --- | --- | --- |
| **4.5C.0** | This planning pass + ADR-0026 | **G0** administrator approves plan/ADR (D22–D31) |
| **4.5C.1** | `ProviderRegistry`/descriptors, rate-limit/concurrency/breaker engine, cross-provider clustering + ranking, all **fake providers**; no network, no manifest, no Room. **Independent of Open Library production approval (D1)**: no Open Library code or endpoint | G0 only |
| **4.5C.2** | `INTERNET` permission, `core.network` helper (D14), Settings master + per-provider toggles and disclosure, **Open Library** ISBN-first provider (metadata only, then cover ids). **Remains gated** | **G1** D1 closed (OL written clarification), D4 contact string, D15; **G2** manifest/privacy review |
| **4.5C.3** | Response cache, `ONLINE` cover slot writes, automatic VERY_HIGH apply, wrong-match rejection, persisted MEDIUM suggestion | G1; **G3** Room version/shape approved (D16/D17) |
| **4.5C.4** (optional) | Library of Congress metadata-only provider | **G1** D22 closed after reading LoC terms |
| **4.5C.5** (optional) | Minimal picker (§4) + USER-slot write | D28; **G4** UX scope approved |
| 4.5D / 4.5E | Unchanged (Manga via ISBN; Comics via Metron per-user token) | D6 / D5+D19 |
| 4.5F | Full Find cover grid, device picker, per-field revert, review indicator | 4.5C.3 merged |

Each slice is its own branch, validation record and Codex High review. A rejected or
unapproved provider is never built "behind a flag". Each provider slice re-reads that
provider's terms at start and records the result in plan §14.

## 7. Access and rights findings (4.5C.P2, 2026-10-19; research only)

Official-page findings that refine, and do not change, the decisions above (details: [`research/PHASE_4_5_PROVIDER_ACCESS_FEASIBILITY.md`](../research/PHASE_4_5_PROVIDER_ACCESS_FEASIBILITY.md)):

- **Open Library has no personal API key.** Public reads are keyless; the only tier is identified (`User-Agent` + contact, 3 req/s) vs non-identified (1 req/s). The use-case
  registration form is informational, not a key. SteamGridDB-style BYOK does not apply. D1/D31 remain unresolved.
- Cover-ID/OLID lookups are not rate-limited per the Covers page, ISBN-keyed lookups are (100 per IP per 5 minutes); crawling and bulk download are prohibited; cover-image
  copyright and offline-storage permission are **not stated**. Cover-source approval stays separate from metadata-source approval.
- BYOK is appropriate only for providers issuing individual credentials (Metron tokens; Hardcover conditionally, D25). BYOK grants no image or caching rights by itself.
- DILVE and the Spanish ISBN Agency are not viable default sources (entity registration and fees; no consumer access path verified). No scraping fallback is authorized.
