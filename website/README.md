# SplitFree website

A frontend-only product website based on the SplitFree intro film v2: near-black,
ivory, mint, citron, Manrope display type, and native-looking phone illustrations.

## Preview locally

Requires Node.js 22 or newer. From the repository root:

```sh
npm --prefix website ci --ignore-scripts
npm --prefix website run dev
```

Open the loopback URL printed by the server (port 4173). The server rebuilds on
startup. After editing, run `npm --prefix website run build` and refresh.
The preview exposes only `website/dist`, never the repository or signing files.
It binds to `127.0.0.1`, not your network interfaces.

## Deploy to GitHub Pages yourself

The workflow is **manual-only for deployment**. Nothing publishes on a push.
Pull requests touching the website get checks, not a deployment.

1. Review and commit the website changes, then make them available in the GitHub
   repository's `mainline` branch through your normal process.
2. In the repository, open **Settings → Pages → Build and deployment**.
   Choose **GitHub Actions** as the source.
3. Open **Actions → Website · GitHub Pages → Run workflow**. Select `mainline`.
4. The workflow installs locked test dependencies, runs checks, and uploads only
   `website/dist`. Its separate deployment job publishes the artifact.
5. Open the actual URL shown by the `github-pages` deployment. No custom domain or
   signing secrets are required. Do not change the repository's Android release workflow.

The page uses relative assets and works under a project path such as `/SplitFree/`
or a domain root. The browser suite tests both. Deployment is restricted to the
original repository and `mainline`; a fork owner must explicitly review/change
that guard to publish their own copy. GitHub may require one-time environment approval.

Alternatively, `npm --prefix website run build` produces a portable `website/dist`
folder that any static host can serve. There is no application server in production.

## GitHub release synchronization

The page makes **one credentialless, read-only request** to the official public
GitHub latest-release API when opened. Version, APK URL, size, release notes and
SHA-256 change together only after validation of a stable published release and
its single expected APK. Drafts, prereleases, missing checksums, unexpected URLs
and ambiguous assets are rejected. No tokens or expense values are sent.

If GitHub is rate-limited, offline, blocked or returns an invalid response, the
bundled release stays usable and the site labels it as a fallback. A separate
“Check latest on GitHub” link is always available. With JavaScript off, visitors
get the clearly labelled bundled release. The site never pretends this is a
successful live check. If a visitor starts a download, opens its release page, or
interacts with its checksum during the check, those details stay pinned together.

Refresh the bundled fallback locally with:

```sh
npm --prefix website run sync:release
```

This reads GitHub and writes only local `site.config.json`. It performs no commit,
remote write or deployment. Manual Pages runs refresh the fallback before tests,
so future visitors without JavaScript also get the release current at deployment.
No automatic publishing workflow is added. API asset checksums are GitHub's
published metadata, not an independent APK signature or security audit.

## GitHub stars

The footer links directly to the repository. One separate credentialless GET reads
GitHub's current star count. Below 100, visitors see only “Star on GitHub”. At 100
or more, the exact count appears beside that invitation. Failed, blocked or
rate-limited checks keep the ordinary link, with no invented or cached count.
Clicking opens GitHub; it does not star automatically or request account access.

## Video storage

The website includes a web-optimized **1920×1080, 30 fps** copy of the intro film, about 6.25 MB, stored as `public/assets/intro.mp4`.
Keep this delivery asset in regular Git when you later publish from your chosen
machine. Do not use Git LFS for the Pages asset: Pages does not serve LFS pointers.
The existing 1080p60 production master is about 19 MB; it remains untouched and
outside the website build. Avoid committing every intermediate render.

The film retains its original INR example; this is one demo currency, not a
geographic restriction. The website starts with the original ₹1,600 example and
retains a 12-currency interactive selector,
including correct zero- and three-decimal currencies, locale-aware results and
neutral language. The currency selector changes the example, not exchange rates.

## Architecture

```text
index.html                  Small page shell with build-time section includes
sections/*.html             Head, header, hero, demo, features, FAQ, footer and film
site.config.json            Bundled release fallback (refreshed from GitHub)
src/styles.css              Brand tokens, section layouts, responsive rules
src/footer.css              Compact footer and GitHub star invitation
src/main.js                 Menu, split preview, film dialog, live release UI
src/release.js              Strict GitHub release validation and bounded GET
src/stars.js                Optional validated GitHub star metadata
src/split.js                Pure currency-aware minor-unit split arithmetic
public/assets/              Self-hosted logo, fonts, film and poster
public/licenses/            Font licenses (also deployed)
scripts/build.mjs           Validates configuration; emits an isolated static site
scripts/templates.mjs       Exact section manifest; safe recursive include assembly
scripts/serve.mjs           Loopback-only preview with video byte-range support
scripts/sync-release.mjs    Read-only GitHub check updating the local fallback
scripts/capture.mjs         Reproducible responsive screenshots (preview running)
tests/unit.test.mjs         Money, release configuration, build-output contracts
tests/browser.test.mjs      Responsive layout, keyboard, a11y, media, fallbacks
dist/                       Generated, ignored; the only deployable folder
```

No framework, router, backend, runtime package dependencies, cookies, analytics,
browser storage, external fonts, or CDN scripts. Two read-only GitHub metadata
requests run on page load: one for the release and one for the star count. The page never reads or uploads app data.
The split preview is illustrative, uses four synthetic friends, and saves nothing.
Its controls are disabled without JavaScript. There is no form to submit or send.
Exact/percent/weighted splits demonstrate preset allocations; the Android app is
where you enter your own members and allocations. Remainders use a deterministic
largest-remainder calculation, not floating-point currency arithmetic.

The film is fetched only after the visitor clicks Watch. There is no autoplay on
page load. It has native playback controls, a close button, Escape handling, a
text description, and a visual-description track. The master video is untouched.

The build joins `sections/*.html` into one complete `dist/index.html`, so navigation,
reading and downloads still work without JavaScript. Includes are limited to an
explicit manifest; unknown, duplicate, circular or unused sections fail the build.
Section sources and build scripts are never deployed or fetched by the browser.

Source files, tests, lockfiles and reviewed delivery assets belong in Git. Local
previews, screenshots/evidence, dependencies, coverage and tool caches are ignored.
Do not hide implementation files simply to make Git status empty.

## Checks

```sh
npm --prefix website test
npm --prefix website exec -- playwright install chromium webkit
npm --prefix website run check
python3 -B tools/release/publication_check.py --source worktree
python3 -B -m unittest discover -s tools/release/tests -p test_website_policy.py -v
```

`check` runs formatting, unit tests, the production build, then Playwright on
Chromium and WebKit on both desktop and touch-enabled mobile profiles. Each project exercises widths from
320 to 1920 px, keyboard interactions, reduced motion, no JavaScript, the
project subpath, and axe WCAG AA checks. Automated accessibility checks supplement,
not replace, testing with actual assistive technology and physical devices.

Use `npm --prefix website run format` before a review. Reports and screenshots
are local ignored outputs. The build copies only its explicit public/source file manifest, rejecting even ignored extra inputs.
The publication policy allows only reviewed source
paths; every binary asset has an exact SHA-256 pin. Replacing a font, poster, or
video requires a provenance review and a matching policy update, not a wildcard.

See [DESIGN.md](DESIGN.md) for visual direction and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for asset provenance.

Mobile regression checks cover every currency label at 320–430 px, maximum input
amounts, native-control appearance, touch interaction, menu focus after rotation,
and the scrollable film transcript with its close control kept reachable. These
are browser/device emulations, not a physical iPhone or Android acceptance test.
