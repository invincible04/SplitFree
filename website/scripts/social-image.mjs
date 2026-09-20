import { chromium } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

// A manual design export, not a build step: reviewed PNG bytes stay stable in CI.
const asset = async (relative, type) =>
    `data:${type};base64,${(await readFile(new URL(relative, import.meta.url))).toString("base64")}`;
const [logo, screenshot, manrope, inter] = await Promise.all([
    asset("../public/assets/splitfree-logo.svg", "image/svg+xml"),
    asset("../../assets/screenshots/groups.png", "image/png"),
    asset("../public/assets/manrope-800.woff2", "font/woff2"),
    asset("../public/assets/inter-variable.woff2", "font/woff2"),
]);
const output = new URL("../public/assets/og-image.png", import.meta.url);
const browser = await chromium.launch();
try {
    const page = await browser.newPage({
        viewport: { width: 1200, height: 630 },
        deviceScaleFactor: 1,
        reducedMotion: "reduce",
    });
    // Every input is a reviewed local asset. Rendering must never need the network.
    await page.route("**/*", (route) => route.abort());
    await page.setContent(`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<title>SplitFree social preview</title>
<style>
@font-face { font-family: Manrope; src: url("${manrope}") format("woff2"); font-weight: 800; }
@font-face { font-family: Inter; src: url("${inter}") format("woff2"); font-weight: 100 900; }
* { box-sizing: border-box; }
body { margin: 0; width: 1200px; height: 630px; overflow: hidden; background: #090c0b; color: #f1f2eb; font-family: Inter, sans-serif; }
.card { position: relative; width: 1200px; height: 630px; overflow: hidden; }
.brand { position: absolute; left: 60px; top: 46px; display: flex; align-items: center; gap: 17px; }
.brand img { width: 40px; height: 53px; }
.brand span { font-family: Manrope, sans-serif; font-size: 32px; letter-spacing: -1.2px; }
h1 { position: absolute; left: 60px; top: 162px; margin: 0; font: 800 70px/1.08 Manrope, sans-serif; letter-spacing: -3.5px; }
h1 span { display: block; white-space: nowrap; }
h1 .accent { color: #c9ed71; }
h1 .first { margin-bottom: 12px; }
.detail { position: absolute; left: 63px; top: 430px; margin: 0; color: #a7afa5; font-size: 25px; letter-spacing: -0.5px; }
.footer { position: absolute; left: 60px; top: 535px; display: flex; align-items: center; gap: 21px; }
.free { border: 1px solid #344339; border-radius: 8px; padding: 12px 17px; color: #c9ed71; font-weight: 700; font-size: 15px; letter-spacing: 1px; }
.android { color: #f1f2eb; font-size: 19px; }
.art { position: absolute; left: 755px; top: 0; width: 445px; height: 630px; }
.orbit { position: absolute; border: 1px solid #263b2e; border-radius: 50%; width: 610px; height: 610px; left: -51px; top: 14px; }
.orbit.inner { width: 465px; height: 465px; left: 22px; top: 87px; background: #13251b; border-color: #2d4835; }
.dot { position: absolute; width: 12px; height: 12px; border-radius: 50%; background: #6bd99a; left: 51px; top: 77px; }
.phone { position: absolute; left: 76px; top: 36px; width: 268px; padding: 8px; background: #101712; border: 1px solid #536050; border-radius: 33px; box-shadow: 0 20px 65px #0009; transform: rotate(5deg); }
.phone img { display: block; width: 250px; height: auto; border-radius: 25px; }
</style>
</head>
<body>
<main class="card">
    <div class="brand"><img src="${logo}" alt="" /><span>SplitFree</span></div>
    <h1><span class="first">Split expenses.</span><span class="accent">Not another</span><span class="accent">subscription.</span></h1>
    <p class="detail">No signup. Works offline.</p>
    <div class="footer"><span class="free">FREE &amp; OPEN SOURCE</span><span class="android">Made for Android</span></div>
    <div class="art" aria-hidden="true">
        <div class="orbit"></div><div class="orbit inner"></div><div class="dot"></div>
        <div class="phone"><img src="${screenshot}" alt="" /></div>
    </div>
</main>
</body>
</html>`);
    await page.evaluate(async () => {
        await document.fonts.ready;
        await Promise.all([...document.images].map((image) => image.decode()));
        if (!document.fonts.check("800 70px Manrope") || !document.fonts.check("400 25px Inter"))
            throw new Error("Social preview fonts did not load");
        for (const element of document.querySelectorAll(".brand, h1, .detail, .footer, .phone")) {
            const rect = element.getBoundingClientRect();
            if (rect.left < 0 || rect.top < 0 || rect.right > 1200 || rect.bottom > 630)
                throw new Error("Social preview content exceeds its canvas");
        }
    });
    await page.screenshot({ path: fileURLToPath(output), type: "png", animations: "disabled" });
    console.log(`Created ${fileURLToPath(output)} (1200 × 630)`);
} finally {
    await browser.close();
}
