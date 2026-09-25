# ADR-0015: Open Source, MPL-2.0, and Official Plus Entitlement

## Status
Accepted

## Context

ShelfOS intends to be a genuine open-source project while allowing official store builds to offer a low-cost one-time Plus/Supporter entitlement.

Client-side feature gating cannot realistically prevent a determined developer from modifying their own build.

The project values community contribution and transparency more than DRM enforcement.

## Decision

1. ShelfOS code is public.
2. Code license is MPL-2.0 (recommendation finalized by the repository LICENSE and ADR-0016).
3. Plus implementation may remain in the public repository.
4. Official Google Play builds may use Play Billing for Plus.
5. Future official iOS builds may use StoreKit.
6. Debug/development builds may simulate Plus for testing.
7. Official releases are distinguished by maintainer-controlled signing and store credentials.
8. Donations may coexist with Plus.
9. Core reading remains useful without Plus.

## Consequences

Positive:

- transparent project
- easier contribution
- no split public/private codebase
- community can improve all areas
- philosophy aligns with user ownership

Tradeoffs:

- technically skilled users can create modified builds
- client-side Plus is not strong DRM
- official branding/release identity needs governance
- store revenue depends partly on goodwill and convenience

## Rationale

ShelfOS does not attempt to win a DRM arms race against its own contributors.

The official product competes on trust, convenience, updates, polish, and support for continued development.
