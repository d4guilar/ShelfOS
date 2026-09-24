# ShelfOS Licensing Strategy

## Adopted project license

**Mozilla Public License 2.0 (MPL-2.0)**

ShelfOS code uses MPL-2.0. The repository root `LICENSE` contains the full license.
This finalizes the earlier recommendation in ADR-0015; see ADR-0016.

## Why MPL-2.0

ShelfOS wants a balance between genuine open-source availability, commercial/store distribution, compatibility with permissively licensed dependencies, and reciprocal sharing of distributed modifications to ShelfOS-covered source files.

MPL uses file-level copyleft. This places it between permissive licenses such as Apache-2.0 and broader copyleft licenses such as GPL.

## Why not MIT / Apache-2.0 as the default

MIT and Apache-2.0 are excellent permissive licenses, but a third party can generally modify the project, redistribute it, and keep those modifications private.

ShelfOS prefers some reciprocity for modifications to its own covered source files.

Apache-2.0 remains a strong alternative if maximum permissiveness later becomes a higher priority.

## Why not GPL-3.0 as the default

GPL-3.0 provides broader copyleft obligations for derivative combined works.

ShelfOS currently prefers MPL-2.0's narrower file-level reciprocity and integration flexibility.

## Dependency compatibility

Every dependency must still be reviewed individually. The project license does not override dependency obligations.

Important examples:

- Readium Kotlin Toolkit is currently BSD-3-Clause.
- Avoid GPL/AGPL dependencies casually.
- PDF/rendering libraries require especially careful license review.
- Readium LCP has additional/private component requirements and is not assumed to be part of ShelfOS.

## Source file headers

For new ShelfOS source files, use the SPDX identifier where appropriate:

```text
SPDX-License-Identifier: MPL-2.0
```

A repository-level `LICENSE` file containing the canonical MPL-2.0 text should also be present.

## Documentation

For simplicity during early development, project documentation may be distributed under MPL-2.0 with the repository. A separate documentation/content license can be adopted later if needed.

## Branding and assets

Open-source code licensing and project branding are separate concerns.

Recommended direction:

- code: MPL-2.0
- community theme code: MPL-2.0 unless stated otherwise
- official ShelfOS name/logo/wordmark: governed by a separate brand policy
- third-party copyrighted assets: never include without permission

If Premium theme artwork/audio assets later need different terms, document those terms explicitly.

## Store distribution

MPL-2.0 permits commercial distribution.

ShelfOS can publish official binaries through Google Play and, later, the Apple App Store while keeping source public.

Store purchases and open-source licensing solve different problems:

- license -> rights to source code
- store entitlement -> access to supported official-build features/services
- signing -> authenticity of official binaries

## Important note

This document is project planning guidance, not legal advice. Before a major commercial release, review the final dependency tree and licensing obligations carefully.
