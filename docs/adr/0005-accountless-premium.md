# ADR-0005: Accountless Premium Entitlement

## Status
Accepted in principle; implementation deferred

## Context
Premium should not require ShelfOS usernames/passwords.

## Decision
When Premium is implemented on Google Play, use a permanent Play Billing entitlement in the same app.

## Consequences
- no ShelfOS authentication system
- simpler UX
- Play-distributed entitlement depends on Google Play account context
- stronger server verification may be added later if justified
