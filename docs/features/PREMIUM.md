# Premium Feature Specification

## Philosophy

Monetization exists to sustain continued development: maintenance, accessibility work,
testing, platform support, documentation and community support.

> **Free users should not feel limited. Paid users should feel rewarded.**

> **ShelfOS Plus does not make ShelfOS more capable. It makes ShelfOS more yours.**

Paid scope stays primarily personalization: official premium themes, curated accents,
optional theme effects, cosmetic extras and Labs / early access. ShelfOS must not
manufacture limitations in core reading or library functionality to create upgrade
pressure.

## Distribution model

One Google Play application.

Free install.

Optional permanent in-app purchase for ShelfOS Plus and premium theme packs later.

No ShelfOS login required.

## Free baseline

ShelfOS Free is the complete core product. Do not intentionally paywall reading,
organization, accessibility, reliability or format support.

Free includes:

- importing supported files, plus EPUB, PDF, CBZ, CBR and other supported formats
- bookmarks, notes and publication search
- reading progress and history
- core Reading Profile / reading-presentation functionality
- managed reader fonts and core typography controls
- Original vs ShelfOS reading presentation where supported, and PDF fidelity work
- metadata editing, Favorites, Series, Shelves, sorting and filtering
- Gallery / Grid / List library views
- accessibility, performance, reliability and bug-fix work
- the five official free themes — Classic, Dark, Pear Platinum, Pear Platinum Dark,
  Deckle — with their standard curated accents
- keyboard, gamepad, offline reading, no ads

Do not introduce book-count limits, shelf-count limits, import quotas, locked formats,
reader-session limits, deliberately inferior rendering or accessibility restrictions.

## ShelfOS Plus

A separate "Theme Pass" is replaced by **ShelfOS Plus**, so ShelfOS does not end up
with overlapping paid products. There is no monthly or yearly Plus, no Bronze/Silver/
Gold tier ladder, no per-theme microtransactions and no Store tab.

Launch direction: **ShelfOS Plus — Lifetime**, target launch/founding price
**$14.99 USD**, one-time. No subscription is used for local cosmetic or personalization
functionality.

Plus includes:

- every current official premium theme pack
- every future **ShelfOS-created** premium theme, with its standard curated accents and
  standard theme effects
- future local personalization extras explicitly released as Plus features
- Labs / experimental early access while that program exists

The price may rise for **new** purchasers as the official collection grows; existing
lifetime owners keep the entitlement they purchased.

Positioning: **Support ShelfOS once. Keep the fun stuff.** Core ShelfOS reading and
library functionality remains free. Avoid fake countdowns, artificial urgency and
manipulative upgrade language.

## Premium theme packs

Users who want one aesthetic family should not have to buy all of Plus. Premium themes
therefore ship as packs:

- **$4.99 USD** each, one-time
- 4–5 themes per pack
- includes each theme's standard curated accents and standard theme effects

Launch packs, 13 themes total: **Retro Systems** (5), **Pop & Print** (4),
**Dream Internet** (4). Theme names, directions, inspiration guardrails and effect
rules live in [Themes](../design/THEMES.md).

Do not sell every premium theme as an individual microtransaction initially. Buying all
three packs costs $14.97, which makes Plus at $14.99 the natural choice for a user who
wants the full official collection and future official themes.

## Theme-pack → Plus upgrade principle

> **Avoid double-charging existing theme-pack supporters when upgrading to ShelfOS Plus
> where platform billing capabilities allow a clean upgrade path.**

One purchased pack should be accounted for in a Plus upgrade; two packs should account
for both. Do not hard-code a billing mechanism until Android/iOS platform billing
capabilities are validated.

## Paid scope beyond themes

Do not create a broad "Pro features" paywall to fill Plus with functionality. Permanent
paid scope remains primarily themes, curated accents, theme effects and cosmetic
personalization extras. Possible future Plus candidates include alternate official app
icons, premium completion/share-card visual styles, and highly polished cosmetic
personalization tools.

Earlier non-cosmetic candidates — advanced stylus tools, advanced PDF reconstruction,
enhanced statistics, smart shelves, advanced metadata management, enhanced comic/manga
tools — remain unresolved long-term candidates rather than finalized entitlement policy.
Their accepted behavior and sequencing live in [Shelves](SHELVES.md) and
[PDF ingestion](PDF_INGESTION.md); this list does not move them into an early phase.

Never paywall bug fixes, accessibility, rendering quality, essential format support,
reliability, performance, normal organization features or core metadata functionality.
Theme purchases/unlocks must never be required for core reading.

## Labs / Early Access

Plus may include Labs, early feature previews and unfinished optional experiments. This
is a supporter benefit, not a mechanism for permanently locking normal ShelfOS
functionality.

> Experimental access may be paid early access, but a feature that later becomes part of
> the expected core ShelfOS experience should be able to graduate to Free.

No promise is made that every experiment ships or that every experimental feature
eventually becomes free.

## Lifetime promise boundary

ShelfOS Plus Lifetime includes future ShelfOS-created premium themes, their standard
curated accents and standard cosmetic effects, and local supporter/personalization
features explicitly designated as Plus features.

It does **not automatically include** third-party licensed collaborations, licensed
commercial assets, hypothetical future hosted services, cloud services with ongoing
per-user infrastructure costs, or every future product or service created under the
ShelfOS name. If ShelfOS later operates a service with real recurring infrastructure
costs, that service is evaluated separately and is never promised as part of Lifetime
Plus. See [local-first and optional future synchronization]
(../ARCHITECTURE.md#local-first-and-optional-future-synchronization).

## Cloud is separate from Plus

A hypothetical future **ShelfOS Cloud** managed service is conceptually separate from
ShelfOS Plus: infrastructure-backed services may require recurring pricing, while Plus
stays a one-time personalization purchase. Exact cloud plans and prices are not committed
anywhere in these docs.

## Product tiers

```text
ShelfOS Free         → complete local-first app
ShelfOS Plus         → lifetime official personalization / supporter benefits
Bring your own cloud → potentially free, because the user supplies the storage
ShelfOS Cloud        → optional paid managed convenience, because ShelfOS operates
                       recurring infrastructure
```

Core philosophy: **users should not need a subscription to properly use their own local
library.**

## Entitlement

Do not spread purchase logic through UI.

Use centralized feature access.

Concept:

```text
Entitlement = FREE | PREMIUM
FeatureAccess.canUse(feature)
```

## Play Billing

Later implementation should:

- use Google Play Billing
- support permanent one-time entitlement
- query owned purchases
- handle pending purchases correctly
- restore entitlement
- acknowledge purchases as required
- consider stronger server-side verification only if the project reaches a scale that justifies it
