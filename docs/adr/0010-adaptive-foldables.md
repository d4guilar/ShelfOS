# ADR-0010: Adaptive Layouts and Foldables Are First-Class

## Status
Accepted

## Context
ShelfOS targets reading workflows that directly benefit from larger and folding displays.

## Decision
ShelfOS must use adaptive layouts from early development.

Layout decisions use current window size/capabilities and fold posture rather than device-model checks.

## Consequences
- better tablets/foldables/ChromeOS behavior
- two-page and multi-pane reading becomes possible
- more state-continuity testing is required
- layout work must account for hinge occlusion and resizing
