# Asset provenance and notices

- **SplitFree code, logo and app design:** this repository, GPL-3.0-or-later.
  The logo is copied unchanged from `assets/splitfree-logo.svg`. Website SVG UI
  symbols and the CSS phone illustrations were authored for this page.
- **Social preview (`assets/og-image.png`):** an original 1200×630 layout rendered
  by `scripts/social-image.mjs` using this repository's unchanged logo, approved
  `assets/screenshots/groups.png` sample screenshot, and the same Manrope/Inter fonts
  listed below. No external artwork or real-user data is added. The PNG is a
  reviewed delivery asset; rendering is manual, not part of the production build.
- **Inter:** the app's Inter variable font, copied from the v2 production assets
  (the same font used by the Android app). SIL Open Font License 1.1.
  Losslessly container-converted to WOFF2 for the web.
  Source: `public/licenses/Inter-OFL.txt`; deployed: `licenses/Inter-OFL.txt`.
- **Manrope:** v2's Manrope 800 display font. Copyright 2018 The Manrope Project
  Authors, SIL Open Font License 1.1. Losslessly container-converted to WOFF2. Full text: `public/licenses/Manrope-OFL.txt` (deployed as `licenses/Manrope-OFL.txt`),
  obtained from `https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/OFL.txt`.
- **Intro film (`assets/intro.mp4`):** a web-optimized delivery copy of the existing 36.933-second
  SplitFree film, 1920×1080 / 30 fps H.264 with AAC audio. Original production
  masters remain unchanged. The poster is a frame from the same film.
- **Music and sound effects in the film:** the existing production uses Mixkit's
  “Trap Electro Vibes” (126) and Mixkit sound effects, under the Mixkit Stock Music
  and Sound Effects licenses. Source: `https://mixkit.co/license/`. These are
  embedded in the finished film, never offered as standalone audio downloads.
  Material icons embedded in the existing film are Apache-2.0 (Google).
  Full license: `public/licenses/Apache-2.0.txt` (deployed: `licenses/Apache-2.0.txt`).
- **Development tools only:** Playwright (Apache-2.0), axe-core / its Playwright
  integration (MPL-2.0), and Prettier (MIT). These are not shipped to the browser.

Film language is marketing shorthand; the website's text and FAQ qualify
“no servers” as no developer-operated backend and explain relay/SDK metadata.
