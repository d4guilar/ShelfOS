# ADR-0008: ShelfOS Is an App, Not a Launcher

## Status
Accepted

## Context
ShelfOS uses OS-like language and frontend inspiration, but users must not feel trapped inside it.

## Decision
ShelfOS remains a normal Android app.

It must not:
- replace the system launcher
- intercept Home
- require kiosk behavior
- block normal Back behavior
- obstruct multitasking

## Consequences
- familiar Android behavior
- easier onboarding
- console-like identity remains a visual/interaction layer rather than device ownership
