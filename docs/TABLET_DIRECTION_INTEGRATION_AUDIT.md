# Tablet field-test and product-direction integration audit

Status: reconciled against the complete 67-section
`SHELFOS_TABLET_FIELD_TEST_AND_PRODUCT_DIRECTION_v3.md` source on 2026-09-25.
The temporary source was deleted after the coverage and consistency checks passed;
the cleaned research record retains the historical evidence.

Detailed specifications live in their destination documents. This file is traceability,
not another product source of truth.

| Area | Permanent destination | Result / intentionally open items |
| --- | --- | --- |
| Sections 1–3, 45, 67: Samsung field test, modest hardware and dedicated-reader opportunity | [research record](research/SAMSUNG_TABLET_FIELD_TEST_2026-09.md), [validation](VALIDATION.md#manual-legacy-tablet-field-evidence) | Integrated as qualitative evidence; Wi-Fi/USB context retained; no invented ADB metrics |
| Sections 4, 64: CBR priority and shared CBZ behavior | [Comics/Manga](features/COMICS_MANGA.md#shared-engine), [roadmap Phase 3](ROADMAP.md#phase-3--comics-and-manga) | Future/high priority; RAR library and license open |
| Sections 60–63, 66: Dawn of X native CBZ and immersive quality bar | [research](research/SAMSUNG_TABLET_FIELD_TEST_2026-09.md#native-cbz), [Comics/Manga fidelity](features/COMICS_MANGA.md#fidelity-and-immersive-presentation) | Positive qualitative evidence; no copied-brand requirement |
| Sections 6, 61, 65: image fidelity and avoid over-processing | [product principles](PRODUCT.md#preserve-source-fidelity), [Comics/Manga](features/COMICS_MANGA.md#fidelity-and-immersive-presentation), [Reader UX](design/READER_UX.md#shared-reader-principles) | Integrated; super-resolution value remains open |
| Sections 5, 17–18: Original/Adapted PDF and Dune observation | [PDF ingestion](features/PDF_INGESTION.md#modes-and-source-preservation), [product](PRODUCT.md#9-libraries-sources-and-organization) | Integrated; exact user wording open |
| Sections 12–14, 16, 22: local OCR and OCR confidence | [PDF ingestion](features/PDF_INGESTION.md#scanned-publications-and-ocr), [ADR-0018](adr/0018-dual-pdf-reading-modes.md) | Local path required; engine/models/languages/packaging/remote policy open |
| Section 15: SourceMap | [PDF ingestion](features/PDF_INGESTION.md#sourcemap-and-annotations), [ADR-0018](adr/0018-dual-pdf-reading-modes.md) | Existing architecture strengthened with OCR confidence |
| Sections 19–20: offline-complete principle | [product](PRODUCT.md#7-offline-complete-online-enhanced), [architecture backend policy](ARCHITECTURE.md#18-backend-policy), [ADR-0002](adr/0002-local-first-accountless.md) | Integrated |
| Sections 21–22, 39: near-zero marginal infrastructure cost | [architecture](ARCHITECTURE.md#18-backend-policy), [open-source model](OPEN_SOURCE_MODEL.md#shelfos-plus--supporter) | Internal sustainability rule; exact Plus boundaries open |
| Sections 23–24, 35–37: default enrichment and BYOK | [metadata](features/METADATA_ENRICHMENT.md#4-provider-architecture), [ADR-0013](adr/0013-metadata-enrichment-provenance.md) | Three layers integrated; provider stack/key UX open |
| Sections 25–26: provider secrets and caching rights | [metadata providers](features/METADATA_ENRICHMENT.md#4-provider-architecture), [local caching](features/METADATA_ENRICHMENT.md#11-local-first-caching) | No embedded secrets; provider-by-provider rights remain open |
| Sections 10, 27, 33–34: beautiful by default and ownership | [product principles](PRODUCT.md#beautiful-by-default), [visual identity](design/VISUAL_IDENTITY.md#6-cover-first-hierarchy) | Integrated |
| Sections 28–32: cover normalization and generated covers | [architecture cover precedence](ARCHITECTURE.md#15-cover-precedence), [visual identity](design/VISUAL_IDENTITY.md#6-cover-first-hierarchy) | Source/presentation split integrated; ratios/crop defaults open |
| Section 11: launcher icon | [visual identity](design/VISUAL_IDENTITY.md#launcher-asset-fidelity-follow-up), [research](research/SAMSUNG_TABLET_FIELD_TEST_2026-09.md#ux-and-visual-observations) | Future polish; cause unconfirmed; not Phase 1 blocker |
| Sections 7, 66: immersive controls | [Reader UX](design/READER_UX.md#comic-reader), [research](research/SAMSUNG_TABLET_FIELD_TEST_2026-09.md#ux-and-visual-observations) | Problem integrated; exact Back/touch/accessibility interaction open |
| Section 8: filename metadata inference | [metadata ingestion](features/METADATA_ENRICHMENT.md#5-ingestion-integration) | Reviewable candidates; user wins |
| Section 9: ComicInfo.xml | [metadata sequential art](features/METADATA_ENRICHMENT.md#8-cbz--sequential-art), [Series](features/SERIES.md#ordering-and-detection) | Structured evidence retained above filename inference |
| Sections 46–48, 52–53, 56–59: Completion Cards and tone | [Reader UX](design/READER_UX.md#completion-experience--future), [roadmap](ROADMAP.md#future--exploration) | Future, optional, quiet satisfaction; no implementation |
| Sections 49–51: completion sharing | [Reader UX](design/READER_UX.md#completion-experience--future) | Local image + system share sheet; no social backend |
| Sections 54–55: completion history/detection | [Reader UX](design/READER_UX.md#completion-experience--future) | Local-first; exact heuristic open |
| Sections 38–39: monetization implications | [open-source model](OPEN_SOURCE_MODEL.md#shelfos-plus--supporter), [product monetization](PRODUCT.md#12-monetization-philosophy) | Local-cost direction integrated; boundaries/pricing open |
| Sections 40, 44: consolidated principles | [product principles](PRODUCT.md#14-product-principles), [architecture](ARCHITECTURE.md#18-backend-policy), [agent rules](../AGENTS.md#ingestion-and-organization-boundaries) | Integrated without repeating every slogan everywhere |
| Sections 41–43: priorities, open questions and non-goals | [roadmap](ROADMAP.md), this audit's open questions, and [agent future-scope rules](../AGENTS.md#do-not-build-yet-unless-explicitly-requested) | Phase numbering/status preserved; future work remains future |

## Checks

- Future CBR, Adapted PDF, OCR, providers, icon work and Completion Cards remain future.
- Phase 1 status and phase numbering were not changed by this reconciliation.
- Existing Generic ZIP semantics remain separate from CBZ and untouched.
- The private launch/audience strategy file was not modified.
- No application code, dependencies, keys or assets were added by this pass.
- All 67 source sections are mapped above; the cleaned research record preserves
  historical evidence while feature details remain in their permanent sources of truth.
- The temporary v3 source was removed only after this audit passed.

## Preserved open questions

This pass does not choose a RAR library/license, OCR engine, model packaging,
multilingual OCR strategy, remote OCR policy, cover ratios, crop/contain defaults,
metadata provider stack, provider caching rights, specialist comic/manga providers,
BYOK storage UX, immersive Back behavior, super-resolution value, completion-detection
heuristic, first-use chrome hint, exact PDF semantic reconstruction, bundled versus
downloaded OCR models, or Plus boundaries/pricing.
