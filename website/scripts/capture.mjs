import { chromium, webkit, devices } from "playwright";
import { mkdir, writeFile } from "node:fs/promises";
const output = new URL("../evidence/", import.meta.url);
await mkdir(output, { recursive: true });
const chromiumBrowser = await chromium.launch();
const webkitBrowser = await webkit.launch();
const checks = [];
for (const [name, width, height, device, engine = "chromium"] of [
    ["desktop", 1440, 1000],
    ["laptop", 1280, 800],
    ["tablet", 768, 1024],
    ["mobile", 393, 851, "Pixel 7"],
    ["iphone", 390, 844, "iPhone 13", "webkit"],
    ["small-mobile", 320, 700, "Pixel 7"],
    ["small-iphone", 320, 700, "iPhone 13", "webkit"],
]) {
    const browser = engine === "webkit" ? webkitBrowser : chromiumBrowser;
    const page = await browser.newPage({
        ...(device ? devices[device] : {}),
        viewport: { width, height },
        deviceScaleFactor: 1,
        reducedMotion: "reduce",
    });
    await page.route("https://api.github.com/repos/invincible04/SplitFree**", (route) =>
        route.abort(),
    );
    await page.goto("http://127.0.0.1:4173/SplitFree/");
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: new URL(`${name}-hero.png`, output).pathname });
    await page.screenshot({ path: new URL(`${name}-full.png`, output).pathname, fullPage: true });
    await page
        .locator(".site-footer")
        .screenshot({ path: new URL(`${name}-footer.png`, output).pathname });
    await page
        .locator(".split-playground")
        .screenshot({ path: new URL(`${name}-demo.png`, output).pathname });
    checks.push(
        await page.evaluate(
            ({ name, width }) => ({
                name,
                width,
                scrollWidth: document.documentElement.scrollWidth,
                scrollHeight: document.documentElement.scrollHeight,
                fonts: document.fonts.status,
                resources: performance
                    .getEntriesByType("resource")
                    .map((x) => ({ name: new URL(x.name).pathname, bytes: x.transferSize })),
                contentClipping: [
                    ...document.querySelectorAll(
                        "h1,h2,h3,.hero-description,.steps p,.feature-card>p,.demo-person,.mode-options",
                    ),
                ]
                    .filter((el) => el.scrollWidth > el.clientWidth + 2)
                    .map((el) => ({
                        tag: el.tagName,
                        class: el.className,
                        scroll: el.scrollWidth,
                        client: el.clientWidth,
                    })),
            }),
            { name, width },
        ),
    );
    await page.close();
}
await writeFile(new URL("visual-checks.json", output), JSON.stringify(checks, null, 2));
await chromiumBrowser.close();
await webkitBrowser.close();
console.log("Screenshots and layout evidence saved to website/evidence/");
