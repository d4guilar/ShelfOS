# ADR-0003: Reader Engines Behind ShelfOS Abstractions

## Status
Accepted

## Context
ShelfOS will likely use Readium and may use other renderers later.

## Decision
Feature/UI code must depend on ShelfOS-owned reader abstractions rather than directly coupling all application logic to one reader SDK.

## Consequences
- easier engine replacement
- easier format-specific behavior
- cleaner testing
- additional adapter code required
