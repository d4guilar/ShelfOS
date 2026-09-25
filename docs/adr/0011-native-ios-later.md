# ADR-0011: Native iOS Later, Portable Product Model Now

## Status
Accepted

## Context
ShelfOS may eventually support iOS/iPadOS, including iPhone Duo and Apple Pencil.

## Decision
Keep Android native and optimize it properly.

Define portable product/data concepts where reasonable so a future Swift/SwiftUI client can interoperate without forcing shared UI code.

## Consequences
- Android development remains simple and idiomatic
- iOS will require a separate client
- backup/export schemas should avoid Android-only identifiers
- cross-platform Premium policy remains a future decision
