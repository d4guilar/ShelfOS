# ADR-0001: Native Android with Kotlin and Jetpack Compose

## Status
Accepted

## Context
ShelfOS is beginning as an Android-first application and needs access to Android file APIs, controllers, keyboards, storage providers, reader SDKs, and future stylus support.

## Decision
Build ShelfOS natively with Kotlin and Jetpack Compose.

## Consequences

Positive:
- first-class Android APIs
- straightforward gamepad/keyboard support
- strong Compose ecosystem
- easier integration with Readium Kotlin
- good resume value as a native Android project

Negative:
- iOS will require a separate implementation later
- team must learn Android-specific architecture and lifecycle behavior
