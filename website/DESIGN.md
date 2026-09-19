# Design system: SplitFree, keep it yours

## Direction

Extend the existing intro film v2, not a generic SaaS template. Its distinctive
rhythm is a near-black introduction, an ivory product demo, a dark encrypted-sync
chapter, and an oversized citron free-software poster. The website turns that
sequence into a readable, self-paced story.

Reference sources: local `videos/splitfree-launch-v2/BRIEF.md`, its frame sheets,
`assets/phone-ui.css`, and the app's actual theme tokens. These video production
files are ignored local materials, not dependencies of the website build.

The requested `.claude/skills/ui-ux-pro-max/SKILL.md` was read and applied for
hierarchy, accessible controls, responsive layout, reduced motion, focus, and
progressive enhancement. That installation contains no database/search scripts;
no claim is made that its design-system generator ran. Product typography and
color override generic recommendations so the site stays faithful to v2.

## Tokens

| Role               | Value                           |
| ------------------ | ------------------------------- |
| Canvas             | `#090c0b`                       |
| Primary ink        | `#f1f2eb`                       |
| Muted dark text    | `#a7afa5`                       |
| Mint action/accent | `#6bd99a`                       |
| Citron statement   | `#c9ed71`                       |
| Ivory chapter      | `#f6f5ef`                       |
| Ivory ink          | `#182019`                       |
| Forest text/action | `#2d5e45`                       |
| Display            | Manrope 800, self-hosted        |
| Body / UI          | Inter variable, self-hosted     |
| Card corners       | 20–24 px                        |
| Button corners     | 9 px                            |
| Maximum canvas     | 1440 px with responsive gutters |

## Hierarchy

1. Hero: product promise, a direct Android download, secondary film link.
2. Principle strip: signup, subscription, offline use, open source.
3. How it works: three real steps and an editable, local-only split example.
4. Features: nearby sync, honest privacy, group organization, currency separation.
5. Citron statement: no subscription, open source, zero price without invented proof.
6. FAQs: practical installation and privacy/recovery caveats.
7. Download: repeated direct action with release notes and the exact checksum.

Phone UI is a scalable HTML/CSS illustration inspired by the real app and video,
not a claim of a pixel-exact screenshot. Sample balances and people are fictional.
The illustration is a labelled image region, not a set of fake clickable controls.
No invented testimonials, download counts, app-store badges, or performance promises.

## Interaction and accessibility

- One dominant action: download the official APK. No modal in the download path.
- Native anchors, radio inputs, details/summary, and dialog preserve keyboard behavior.
- Phone navigation expands inline below the header; Escape and selection close it.
- Touch targets for essential actions are at least 44 px.
- All meaningful text is HTML, not embedded in images.
- Content is visible before JavaScript; animation never gates reading.
- Short one-shot entrance animations and hover feedback; no scroll hijacking,
  custom cursor, perpetual ticker, background film, or animated financial counters.
- Reduced-motion and forced-colors modes are respected.
- All runtime assets are first-party; no analytics, external font calls, or storage.
  Two read-only GitHub metadata requests keep release details and the optional star count current.
- Privacy text does not equate encryption with anonymity or claim no third-party servers.

## Footer and examples

The footer ends the story with a compact logo, short tagline and a real GitHub star
invitation, not a second oversized wordmark. Counts appear only at 100 stars or more.
The hero and initial split example use ₹1,600, while language stays worldwide and
the demo keeps its other currencies. Sources are organized into build-time HTML sections.
