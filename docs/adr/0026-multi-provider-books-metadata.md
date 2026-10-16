# ADR-0026: Modular multi-provider Books metadata and cover discovery

## Status

**Accepted** 2026-10-16 (Phase 4.5C.0, planning only) via plan D23, with D30 approved in its
corrected form (`docs/PHASE_4_5_IMPLEMENTATION_PLAN.md` §31). 4.5C.1 may proceed without D1;
D1 and D31 remain open gates for production Open Library access (4.5C.2). Nothing is
implemented; no network, manifest, dependency or Room change is authorized by this ADR.

## Context

Plan §11 framed 4.5C as one Open Library integration. Research on 2026-10-16
(`docs/features/BOOK_PROVIDERS.md`) shows that only Open Library is keyless, independent,
edition-level and cover-bearing, and it is still unapproved (D1). Library of Congress is a
rate-limited metadata catalog; Gutendex is a hobby-hosted public-domain wrapper; Hardcover and
OverDrive are credentialed; Google Books stays rejected (D2); bookcover-api wraps Goodreads.
ShelfOS is open source, free-core, donation-supported with optional paid cosmetic themes, so
every provider is assessed as a commercial-model distributed app, and a public endpoint is not
a redistribution licence.

## Decision (proposed)

1. Replace the single-provider design with a **compiled-in provider registry**. A provider is
   active only when its `termsStatus` is `APPROVED` (set only by an approved decision row), the
   global online toggle and its own toggle are on, and any required user token exists.
2. Providers stay behind `MetadataProvider`; UI never imports one. Zero active providers is a
   valid, fully working state; provider failure never blocks import or reading.
3. ISBN-first lookup, bounded search fallback, lazy per-item requests, per-provider token
   bucket, bounded concurrency, `Retry-After`/backoff and a circuit breaker.
4. Offline-first response and cover caching in the existing `ONLINE` slot; no hotlinking;
   provider data and covers remain out of exports/backups.
5. Cross-provider clustering by ISBN-13; provider agreement ranks or corroborates candidates
   but never bypasses the 4.5B edition, identifier or contradiction rules; disagreement
   demotes; different editions are never merged by title.
6. Automatic apply only for VERY_HIGH matches. Cover precedence is the accepted plan §17
   order (`USER > confident high-quality ONLINE > usable embedded > generated`), so a Books
   online cover may replace a usable embedded cover when it passes the §17 quality gate;
   D7 still forbids automatic replacement of embedded CBZ/CBR covers for Comics/Manga.
   Everything else is manual. USER choices always win.
7. Per-provider attribution and privacy disclosure; keyed providers are BYOK only.
8. Rejected providers (Google Books, OverDrive default, Gutendex public instance,
   bookcover-api/Goodreads) are not built behind flags.

## Consequences

- 4.5C can ship with one provider; adding another is a registry entry plus its own approval
  gate and slice, not an architecture change.
- More upfront design (registry, clustering) than a single client; it is built and tested with
  fakes first (4.5C.1) before any network permission.
- 4.5C.1 (registry, scheduler, clustering, fakes) does not depend on D1; 4.5C.2, the first
  slice with `INTERNET` or Open Library code, stays gated by D1, which is the critical path for
  any online Books enrichment.

## Alternatives considered

- **Open Library only, no registry** — simplest, but hard-codes a conditional provider and
  makes the next provider a refactor.
- **Aggregate every public endpoint (Google Books, Goodreads wrapper)** — rejected; fee,
  credential, caching and scraping-rights conflicts.
