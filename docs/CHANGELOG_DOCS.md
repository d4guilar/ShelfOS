# Documentation Changelog

## Phase 1 review remediation (2026-09-24)

- Corrected Phase 1 status (README, roadmap, Phase 1 plan, validation): 1Aâ€“1C implemented,
  1D partial; open software gaps listed alongside the physical-device gates.
- Named the three samples whose hashes were verified; the large CBZ was not hashed.
- Recorded the review fixes and their evidence by category (static/build, unit/regression,
  emulator, physical device), and the import ownership rule in the architecture.

## Phase 1 implementation status (2026-09-24)

- Recorded Phase 1 as implemented and emulator-validated but not accepted (README,
  roadmap, Phase 1 plan, architecture, validation); physical-device gates stay open.
- Marked the Shelves rename and non-destructive private-copy removal as done.
- Documented descriptor-based archive access (shared storage cannot be reopened by path),
  startup maintenance, the error model and reader input/focus behavior.
- Recorded the measured debug APK size and a dependency-inventory check; no dependency changed.

## Accepted ingestion and organization decisions (2026-09-24)

- Added dedicated Data Ingestion, PDF Ingestion, Series, Shelves and Library Sources specifications.
- Added ADRs 0018â€“0022 and a requirement-by-requirement integration audit.
- Reconciled Collections â†’ Shelves, pre-commit metadata â†’ post-commit enrichment,
  Source/session identity, PDF mode terminology and safe managed-source behavior.
- Updated product/architecture/roadmap, Phase 1 status, feature/design and contributor guidance.
- Preserved Phase 0 validation as historical evidence and unfinished Phase 1 code unchanged.
- This reconciliation implements no application functionality; accepted targets remain phased.

## Phase 1 implementation plan (2026-09-23)

- Added PHASE_1_PLAN and proposed ADR-0017 for an expanded library/first-reader build.
- Recorded the change from original phase sequencing instead of silently moving scope.
- Distinguished reflowable typography from PDF reconstruction and image-page limits.
- Planned Manga PDF/CBZ direction, stable page identity and user preference precedence.
- Specified staged schemas, private fixtures, large-file handling and acceptance gates.
- Preserved the Phase 0 implementation status; no reader code or dependencies added.

## Phase 0 implementation revision

- Imported the supplied documentation bundle into canonical root/docs paths.
- Added ADR-0016 covering scope, identity, persistence, input and adaptive behavior.
- Reconciled the obsolete four-theme architecture list against accepted ADR-0012.
- Finalized MPL-2.0 adoption against the existing LICENSE and owner direction.
- Clarified that suggested publication schemas and metadata work are future phases.
- Added dependency review, validation instructions and actual prototype status.

## v4 â€” Library Foundation + Metadata Enrichment

This revision incorporates conclusions from the approved ShelfOS Classic Library mock.

Added:

- approved UI reference image
- detailed Classic Library implementation reference
- Metadata Enrichment specification
- metadata provider/provenance architecture
- expanded tablet/foldable detail-pane behavior
- compact phone details behavior
- explicit theme structural boundaries
- ADR-0013 metadata enrichment with provenance
- ADR-0014 themes preserve information architecture

Updated:

- Library
- Import
- Classic UI
- Themes
- Architecture
- Product
- Roadmap
- AGENTS.md
- README

Core decision:

> Themes are visual/interaction layers over one ShelfOS structure. Metadata enrichment is part of the library model, not a theme feature.


## v5 â€” Open Source, Licensing, and Release Governance

Added:

- `OPEN_SOURCE_MODEL.md`
- `LICENSING.md`
- `RELEASE_GOVERNANCE.md`
- root `CONTRIBUTING.md`
- root `SECURITY.md`
- draft brand policy
- pull-request template
- ADR-0015

Decisions:

- recommend MPL-2.0
- full source remains public
- Plus implementation may remain public
- no DRM arms race
- debug builds may simulate Plus
- official releases are controlled by signing/store credentials
- `main` should be protected and changed through reviewed pull requests
