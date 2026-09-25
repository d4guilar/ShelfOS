# ShelfOS Open-Source Model

## Status

Accepted project direction.

ShelfOS is an open-source, local-first personal reading system. The public repository contains the real application source, including implementation for features that may be part of the official ShelfOS Plus/Supporter experience.

The project does not attempt to make client-side feature gating impossible to bypass.

## Why

ShelfOS is built around ownership, user control, transparency, local-first software, community contribution, and long-term preservation.

A hostile DRM model would conflict with those goals and create significant engineering overhead. Technically capable users may be able to build or modify their own APKs. This is an accepted tradeoff.

## Official distribution model

### GitHub

Used for public source, development, issues, pull requests, contributor builds, and tagged source releases.

### Google Play

Used for easy installation, trusted signing, automatic updates, discovery, official ShelfOS builds, and an optional ShelfOS Plus/Supporter purchase.

### Future App Store

Used for official iPhone/iPad distribution, trusted signing, updates, and a StoreKit-based optional Plus/Supporter entitlement.

## ShelfOS Plus / Supporter

Official store builds may offer a low-cost, one-time entitlement.

The purchase primarily supports development and may unlock additional theme experiences, advanced customization, and selected power-user features.

Core reading must not be intentionally degraded for free users.

Core ShelfOS functionality should approach zero marginal infrastructure cost per
user. Favor local execution for reading, indexing, OCR/PDF analysis, search, notes,
Series and Shelves. Plus/supporter value should generally come from local themes,
layouts, customization and power-user convenience rather than recurring server
costs. Exact Plus boundaries and pricing remain open.

The public source may contain Plus feature implementation. Debug/development builds may expose a developer entitlement override so contributors can work on Plus features without purchasing them.

## No DRM arms race

ShelfOS should not spend significant effort trying to prevent a determined developer from modifying a local build, changing entitlement logic, compiling a custom APK, or creating a fork.

Official ShelfOS should provide value through convenience, trusted releases, updates, polished store distribution, optional supporter purchases, and community trust.

## Forks

Forks are welcome.

Typical workflow:

1. Fork ShelfOS.
2. Create a focused feature/fix branch.
3. Submit a pull request.
4. Pass automated checks.
5. Receive review.
6. Merge only after approval.

Forks do not automatically affect the official application.

## Official vs unofficial builds

An official ShelfOS build is produced by the maintainers' release process and signed with official distribution credentials.

Third-party builds should not imply they are official ShelfOS releases.

A future brand policy may require publicly redistributed forks to use distinguishable naming and artwork.

## Donations

ShelfOS may accept optional support through GitHub Sponsors, Ko-fi, Open Collective, or similar services.

Donations must not be required to use core ShelfOS.

## Principles

> Open source is part of ShelfOS, not merely a source-code mirror.

> Plus supports development; it does not hold the user's library hostage.

> Official distribution is protected by signing and release governance, not by hiding the source.
