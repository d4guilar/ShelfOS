# Documentation Changelog

## Phase 0 implementation revision

- Imported the supplied documentation bundle into canonical root/docs paths.
- Added ADR-0016 covering scope, identity, persistence, input and adaptive behavior.
- Reconciled the obsolete four-theme architecture list against accepted ADR-0012.
- Finalized MPL-2.0 adoption against the existing LICENSE and owner direction.
- Clarified that suggested publication schemas and metadata work are future phases.
- Added dependency review, validation instructions and actual prototype status.

## v4 — Library Foundation + Metadata Enrichment

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


## v5 — Open Source, Licensing, and Release Governance

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
