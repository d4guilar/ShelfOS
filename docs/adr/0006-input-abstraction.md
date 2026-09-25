# ADR-0006: Semantic Input Abstraction

## Status
Accepted

## Context
ShelfOS targets touch devices but should also work well with keyboards and gamepads.

## Decision
Translate raw touch/key/controller events into semantic ShelfOS commands.

## Consequences
- consistent behavior across hardware
- easier remapping later
- improved testing
- requires deliberate focus/navigation design
