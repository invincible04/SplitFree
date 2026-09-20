import { test, expect } from "@playwright/test";
import { STARS_API, starLabel } from "../src/stars.js";
import { RELEASE_API, releaseFixture } from "./release-fixture.mjs";
import AxeBuilder from "@axe-core/playwright";
import { readFile } from "node:fs/promises";
const config = JSON.parse(await readFile(new URL("../site.config.json", import.meta.url), "utf8"));

const starsFixture = (count) => ({
    full_name: "invincible04/SplitFree",
    html_url: config.repository,
    private: false,
    stargazers_count: count,
});
test.beforeEach(async ({ page }) => {
    await page.route(STARS_API, (route) => route.fulfill({ json: starsFixture(42) }));
    await page.route(RELEASE_API, (route) =>
        route.fulfill({ json: releaseFixture(config.release) }),
    );
});

test("loads the project subpath without errors or unexpected third-party requests", async ({
    page,
}) => {
    const errors = [];
    const failures = [];
    const external = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("response", (response) => {
        if (response.status() >= 400) failures.push(`${response.status()} ${response.url()}`);
    });
    page.on("request", (request) => {
        if (
            new URL(request.url()).origin !== "http://127.0.0.1:4173" &&
            request.url() !== RELEASE_API &&
            request.url() !== STARS_API
        )
            external.push(request.url());
    });
    await page.goto("/SplitFree/");
    await page.evaluate(() => document.fonts.ready);
    await expect(page.locator("#release-status")).toContainText("Latest published release");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
        "Split expenses.Keep it yours.",
    );
    await expect(page.locator("[data-download]")).toHaveCount(3);
    for (const [label, file, text] of [
        ["License", "LICENSE.txt", "GNU GENERAL PUBLIC LICENSE"],
        ["Notices", "THIRD-PARTY-NOTICES.txt", "Material icons"],
    ]) {
        const link = page
            .getByRole("navigation", { name: "Footer navigation" })
            .getByRole("link", { name: label, exact: true });
        await expect(link).toHaveAttribute("href", file);
        const response = await page.request.get(new URL(file, page.url()).href);
        expect(response.status()).toBe(200);
        expect(await response.text()).toContain(text);
    }
    for (const link of await page.locator("[data-download]").all())
        await expect(link).toHaveAttribute("href", config.release.apkUrl);
    expect(errors).toEqual([]);
    expect(failures).toEqual([]);
    expect(external).toEqual([]);
    expect(await page.evaluate(() => document.cookie)).toBe("");
    expect(await page.evaluate(() => localStorage.length)).toBe(0);
    expect(await page.locator("video source").getAttribute("src")).toBeNull();
});

test("all responsive widths fit without horizontal overflow", async ({ page }) => {
    for (const width of [320, 375, 393, 699, 700, 768, 959, 1024, 1280, 1440, 1920]) {
        await page.setViewportSize({ width, height: 900 });
        await page.goto("/");
        await page.evaluate(() => document.fonts.ready);
        expect(
            await page.evaluate(() => document.documentElement.scrollWidth),
            `overflow at ${width}`,
        ).toBeLessThanOrEqual(width);
        await expect(page.locator("h1")).toBeVisible();
        await expect(page.locator(".hero [data-download]")).toBeVisible();
    }
});

test("hero selection layers preserve the original compact layout and selectable text", async ({
    page,
}) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    for (const width of [320, 393, 768, 1280, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        await page.goto("/SplitFree/");
        await page.evaluate(() => document.fonts.ready);
        const result = await page.locator("#hero-title").evaluate((el) => {
            const lead = el.querySelector(".hero-title-lead");
            const geometry = () => {
                const nodes = [
                    el,
                    lead,
                    el.querySelector(".mint"),
                    document.querySelector(".hero-description"),
                ];
                return nodes.map((node) => {
                    const rect = node.getBoundingClientRect();
                    return [rect.x, rect.y, rect.width, rect.height];
                });
            };
            const style = getComputedStyle(el);
            const lineRatio = parseFloat(style.lineHeight) / parseFloat(style.fontSize);
            const before = geometry();
            lead.style.position = "static";
            const unlayered = geometry();
            lead.style.removeProperty("position");
            const selected = [];
            for (const part of ["second", "first", "all"]) {
                const range = document.createRange();
                if (part === "second") {
                    range.setStartAfter(el.querySelector("br"));
                    range.setEndAfter(el.querySelector(".mint"));
                } else range.selectNodeContents(part === "first" ? lead : el);
                window.getSelection().removeAllRanges();
                window.getSelection().addRange(range);
                selected.push(window.getSelection().toString().replace(/\s+/g, " ").trim());
            }
            const after = geometry();
            window.getSelection().removeAllRanges();
            return { lineRatio, before, unlayered, after, selected };
        });
        expect(result.lineRatio).toBeCloseTo(1.045, 3);
        expect(result.before).toEqual(result.unlayered);
        expect(result.after).toEqual(result.before);
        expect(result.selected[0]).toBe("Keep it yours.");
        expect(result.selected[1]).toBe("Split expenses.");
        expect(result.selected[2].replace(/\s/g, "")).toBe("Splitexpenses.Keepityours.");
        await expect(page.locator(".hero-title-lead")).toHaveCSS("position", "relative");
        await expect(page.locator(".hero-title-lead")).toHaveCSS("z-index", "1");
        await expect(page.locator("#hero-title")).not.toHaveCSS("user-select", "none");
    }
});

test("interactive examples, keyboard radio navigation and invalid amounts", async ({ page }) => {
    await page.goto("/");
    await page.locator("#split-demo").scrollIntoViewIfNeeded();
    const amount = page.locator("#bill-amount");
    await expect(page.locator('[data-person="0"]')).toHaveText("₹400.00");
    await amount.fill("2000");
    await expect(page.locator('[data-person="0"]')).toHaveText("₹500.00");
    await page.getByLabel("Exact", { exact: true }).check();
    await expect(page.locator('[data-person="0"]')).toHaveText("₹625.00");
    await expect(page.locator("#demo-note")).toContainText("sample custom-amount");
    await page.getByLabel("Percent", { exact: true }).check();
    await expect(page.locator('[data-person="0"]')).toHaveText("₹800.00");
    await page.getByLabel("Percent", { exact: true }).focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByLabel("Shares", { exact: true })).toBeChecked();
    await amount.fill("1.01");
    await page.getByLabel("Equal", { exact: true }).check();
    await expect(page.locator('[data-person="0"]')).toHaveText("₹0.26");
    await expect(page.locator("#split-summary")).toContainText("adds up exactly");
    await amount.fill("bad");
    await expect(amount).toHaveAttribute("aria-invalid", "true");
    await expect(page.locator("#amount-error")).toBeVisible();
    await expect(page.locator('[data-person="0"]')).toHaveText("—");
    await amount.fill("1600");
    await expect(page.locator("#amount-error")).toBeHidden();
    await expect(page.locator('[data-person="0"]')).toHaveText("₹400.00");
});

test("mobile menu opens, closes by Escape, link, outside click and resize", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/");
    const menu = page.getByRole("button", { name: "Open menu" });
    await expect(menu).toBeVisible();
    await expect(page.locator("#site-nav")).toBeHidden();
    await menu.click();
    await expect(page.locator("#site-nav")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(menu).toBeFocused();
    await expect(page.locator("#site-nav")).toBeHidden();
    await menu.click();
    await page.locator('#site-nav a[href="#how-it-works"]').click();
    await expect(page.locator("#site-nav")).toBeHidden();
    await expect(page).toHaveURL(/#how-it-works$/);
    await menu.click();
    await page.locator("h2#how-title").click();
    await expect(page.locator("#site-nav")).toBeHidden();
    await menu.click();
    await page.setViewportSize({ width: 1280, height: 900 });
    await expect(page.locator(".menu-toggle")).toBeHidden();
    await expect(page.locator("#site-nav")).toBeVisible();
});

test("native FAQ is keyboard usable and checksum is visible", async ({ page }) => {
    await page.goto("/");
    const question = page.locator("summary").filter({ hasText: "Is it actually free?" });
    await question.focus();
    await page.keyboard.press("Enter");
    await expect(page.locator("details[open]")).toContainText("GPL-3.0-or-later");
    await page.getByText("Verify your download", { exact: true }).click();
    await expect(page.locator("#apk-checksum")).toHaveText(config.release.sha256);
});

test("film opens on demand, traps focus, plays and pauses when closed", async ({ page }) => {
    await page.goto("/");
    await page.locator(".film-trigger").click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await expect(page.locator("video source")).toHaveAttribute("src", "assets/intro.mp4");
    await expect
        .poll(() => page.locator("video").evaluate((el) => el.readyState), { timeout: 15000 })
        .toBeGreaterThanOrEqual(2);
    expect(await page.locator("video").evaluate((el) => el.duration)).toBeCloseTo(36.93, 0);
    expect(await page.locator("video").evaluate((el) => [el.videoWidth, el.videoHeight])).toEqual([
        1920, 1080,
    ]);
    await page.keyboard.press("Tab");
    expect(await page.evaluate(() => document.activeElement.closest("dialog") !== null)).toBe(true);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toBeHidden();
    await expect.poll(() => page.locator("video").evaluate((el) => el.paused)).toBe(true);
    await expect(page.locator(".film-trigger")).toBeFocused();
});

test("WCAG AA automated audit on page and open FAQ", async ({ page }) => {
    await page.goto("/");
    await page.evaluate(() => document.fonts.ready);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.evaluate(() =>
        document
            .querySelectorAll(".faq-list details,.checksum-details")
            .forEach((el) => (el.open = true)),
    );
    const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
        .analyze();
    expect(
        results.violations.map((v) => ({
            id: v.id,
            nodes: v.nodes.map((n) => ({ target: n.target, summary: n.failureSummary })),
        })),
    ).toEqual([]);
});

test("no-JS keeps navigation, downloads, FAQ and film fallback working", async ({ browser }) => {
    const context = await browser.newContext({
        javaScriptEnabled: false,
        viewport: { width: 375, height: 812 },
    });
    const page = await context.newPage();
    await page.goto("http://127.0.0.1:4173/SplitFree/");
    await expect(page.locator("h1")).toBeVisible();
    await expect(page.locator("#site-nav")).toBeVisible();
    await expect(page.locator(".menu-toggle")).toBeHidden();
    await expect(page.locator("#bill-amount")).toBeDisabled();
    await expect(page.getByLabel("Equal", { exact: true })).toBeDisabled();
    await page.locator("#split-demo").click();
    await page.keyboard.press("Enter");
    expect(new URL(page.url()).search).toBe("");
    await expect(page.locator(".hero [data-download]")).toHaveAttribute(
        "href",
        config.release.apkUrl,
    );
    await page.locator("summary").filter({ hasText: "Is it actually free?" }).click();
    await expect(page.locator("details[open]")).toBeVisible();
    await expect(page.locator(".film-trigger")).toHaveAttribute("href", "assets/intro.mp4");
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
        375,
    );
    await context.close();
});

test("reduced motion, zoomed layout and mobile touch controls", async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/");
    expect(
        await page.evaluate(() => getComputedStyle(document.documentElement).scrollBehavior),
    ).toBe("auto");
    for (const selector of [
        ".menu-toggle",
        ".hero [data-download]",
        ".film-trigger",
        ".mode-options span",
        ".faq-list summary",
    ])
        for (const element of await page.locator(selector).all()) {
            const box = await element.boundingBox();
            expect(box.height, selector).toBeGreaterThanOrEqual(44);
        }
    await page.setViewportSize({ width: 640, height: 450 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
        640,
    );
    await page.addStyleTag({ content: "body { zoom: 2; }" });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
        640,
    );
});

test("static server supports video ranges and never exposes source files", async ({ request }) => {
    const response = await request.get("/assets/intro.mp4", {
        headers: { Range: "bytes=0-99" },
    });
    expect(response.status()).toBe(206);
    expect((await response.body()).length).toBe(100);
    expect(
        (
            await request.get("/assets/intro.mp4", {
                headers: { Range: "bytes=999999999-" },
            })
        ).status(),
    ).toBe(416);
    for (const url of [
        "/site.config.json",
        "/package.json",
        "/scripts/build.mjs",
        "/sections/footer.html",
        "/assets/splitfree-intro-v2.mp4",
        "/.git/config",
        "/missing",
    ])
        expect((await request.get(url)).status()).toBe(404);
});

test("failed script download leaves demo inert and primary links usable", async ({ page }) => {
    await page.route("**/src/main.js", (route) => route.abort());
    await page.goto("/SplitFree/");
    await expect(page.locator("#bill-amount")).toBeDisabled();
    await expect(page.locator("#site-nav")).toBeVisible();
    await expect(page.locator(".hero [data-download]")).toHaveAttribute(
        "href",
        config.release.apkUrl,
    );
    expect(await page.locator("form").count()).toBe(0);
});

test("checksum copy reports both success and browser denial", async ({ page }) => {
    await page.addInitScript(() => {
        Object.defineProperty(navigator, "clipboard", {
            configurable: true,
            value: {
                writeText: async (text) => {
                    window.copiedChecksum = text;
                },
            },
        });
    });
    await page.goto("/");
    await page.getByText("Verify your download", { exact: true }).click();
    await page.getByRole("button", { name: "Copy checksum" }).click();
    expect(await page.evaluate(() => window.copiedChecksum)).toBe(config.release.sha256);
    await expect(page.locator("#copy-status")).toHaveText("Copied.");
    await page.evaluate(() => {
        navigator.clipboard.writeText = async () => {
            throw new Error("Not permitted");
        };
    });
    await page.getByRole("button", { name: "Copy checksum" }).click();
    await expect(page.locator("#copy-status")).toHaveText("Select and copy the checksum above.");
});

test("broken video shows an accessible direct-link fallback", async ({ page }) => {
    await page.route("**/intro.mp4", (route) => route.abort());
    await page.goto("/");
    await page.locator(".film-trigger").click();
    await expect(page.locator("#film-error")).toBeVisible();
    await expect(page.locator("#film-error a")).toHaveAttribute("href", "assets/intro.mp4");
    await page.getByRole("button", { name: "Close film" }).click();
    await expect(page.locator(".film-trigger")).toBeFocused();
});

for (const recovers of [false, true]) {
    test(`failed film retries on reopen and ${recovers ? "recovers without resetting healthy playback" : "keeps the fallback after another failure"}`, async ({
        page,
    }) => {
        await page.emulateMedia({ reducedMotion: "reduce" });
        await page.addInitScript(() => {
            const load = HTMLMediaElement.prototype.load;
            HTMLMediaElement.prototype.load = function () {
                this.dataset.loadCalls = String(Number(this.dataset.loadCalls || 0) + 1);
                return load.call(this);
            };
        });
        let retry = false;
        let requests = 0;
        let releaseRetry;
        const held = new Promise((resolve) => {
            releaseRetry = resolve;
        });
        await page.route("**/intro.mp4", async (route) => {
            requests++;
            if (!retry) return route.abort();
            await held;
            return recovers ? route.continue() : route.abort();
        });
        try {
            await page.goto("/SplitFree/");
            expect(requests).toBe(0);
            const video = page.locator("#intro-film");
            const fallback = page.locator("#film-error");
            await page.locator(".film-trigger").click();
            await expect(fallback).toBeVisible();
            await expect.poll(() => video.evaluate((el) => el.networkState)).toBe(3);
            await expect(video).toHaveAttribute("data-load-calls", "1");
            await page.locator(".film-close").click();
            await expect(page.locator(".film-trigger")).toBeFocused();
            const failedRequests = requests;
            retry = true;
            await page.locator(".film-trigger").click();
            await expect(video).toHaveAttribute("data-load-calls", "2");
            await expect.poll(() => requests).toBeGreaterThan(failedRequests);
            // The original error link must remain available while a retry is pending.
            await expect(fallback).toBeVisible();
            await expect(fallback.locator("a")).toHaveAttribute("href", "assets/intro.mp4");
            if (recovers) {
                // Closing a pending retry must not create another load or discard its fallback.
                await page.locator(".film-close").click();
                await expect.poll(() => video.evaluate((el) => el.paused)).toBe(true);
                await page.locator(".film-trigger").click();
                await expect(video).toHaveAttribute("data-load-calls", "2");
                await expect(fallback).toBeVisible();
            }
            releaseRetry();
            if (recovers) {
                await expect
                    .poll(() => video.evaluate((el) => el.readyState), { timeout: 15000 })
                    .toBeGreaterThanOrEqual(3);
                await expect(fallback).toBeHidden();
                await expect.poll(() => video.evaluate((el) => el.currentTime)).toBeGreaterThan(0);
                await page.locator(".film-close").click();
                await expect.poll(() => video.evaluate((el) => el.paused)).toBe(true);
                await video.evaluate((el) => {
                    el.currentTime = 5;
                });
                await expect.poll(() => video.evaluate((el) => el.seeking)).toBe(false);
                await page.locator(".film-trigger").click();
                await expect(video).toHaveAttribute("data-load-calls", "2");
                expect(await video.evaluate((el) => el.currentTime)).toBeGreaterThanOrEqual(5);
                await expect(fallback).toBeHidden();
            } else {
                await expect.poll(() => video.evaluate((el) => el.networkState)).toBe(3);
                await expect(fallback).toBeVisible();
            }
            await page.locator(".film-close").click();
            await expect(page.locator(".film-trigger")).toBeFocused();
        } finally {
            releaseRetry();
        }
    });
}

for (const errorName of ["NotAllowedError", "AbortError"]) {
    test(`film ${errorName} does not become a false load failure`, async ({ page }) => {
        await page.addInitScript((name) => {
            HTMLMediaElement.prototype.play = function () {
                this.preload = "auto";
                return Promise.reject(new DOMException("Synthetic playback interruption", name));
            };
        }, errorName);
        await page.goto("/");
        await page.locator(".film-trigger").click();
        await expect
            .poll(() => page.locator("video").evaluate((el) => el.readyState), { timeout: 15000 })
            .toBeGreaterThanOrEqual(3);
        await expect(page.locator("#film-error")).toBeHidden();
        await expect(page.locator("video")).toHaveAttribute("controls", "");
        await page.locator(".film-close").click();
    });
}

test("demo never sends amounts or changes the URL when Enter is pressed", async ({ page }) => {
    await page.goto("/SplitFree/");
    const requests = [];
    page.on("request", (request) => {
        if (/^https?:/.test(request.url())) requests.push(request.url());
    });
    await page.locator("#bill-amount").fill("9876.54");
    await page.locator("#bill-amount").press("Enter");
    await expect(page.locator('[data-person="0"]')).toHaveText("₹2,469.14");
    expect(page.url()).toBe("http://127.0.0.1:4173/SplitFree/");
    expect(requests.filter((url) => url.includes("9876") || url.includes("amount="))).toEqual([]);
});

test("keyboard disclosure enters navigation and film has full visual transcript", async ({
    page,
    browserName,
}) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/");
    await page.getByRole("button", { name: "Open menu" }).focus();
    await page.keyboard.press("Enter");
    await expect(page.locator("#site-nav a").first()).toBeFocused();
    // Safari uses Option-Tab to include links when Full Keyboard Access is off.
    await page.keyboard.press(browserName === "webkit" ? "Alt+Tab" : "Tab");
    await expect(page.locator("#site-nav a").nth(1)).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", { name: "Open menu" })).toBeFocused();
    await page.locator("#film-transcript summary").click();
    await expect(page.locator("#film-transcript li")).toHaveCount(9);
    await expect(page.locator("#film-transcript")).toContainText(
        "relay and Google Play services metadata",
    );
});

test("film popup is uncluttered while the text alternative stays in the FAQ", async ({ page }) => {
    await page.goto("/SplitFree/");
    await page.locator(".film-trigger").click();
    const popup = page.getByRole("dialog");
    await expect(popup.locator("details")).toHaveCount(0);
    await expect(popup).not.toContainText("37 seconds of SplitFree");
    await expect(popup).not.toContainText("Read the film’s visual description");
    await expect(popup.locator("video")).toHaveAccessibleName("SplitFree intro film");
    await expect(popup.locator("#film-description")).toHaveClass("sr-only");
    await expect(page.locator("#film-transcript")).not.toHaveAttribute("open", "");
    const results = await new AxeBuilder({ page })
        .include("#film-dialog")
        .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
        .analyze();
    expect(results.violations.map((v) => v.id)).toEqual([]);
    // Wait for queued close/focus restoration before moving focus to the FAQ.
    const closed = popup.evaluate(
        (el) =>
            new Promise((resolve) => el.addEventListener("close", () => resolve(), { once: true })),
    );
    await page.getByRole("button", { name: "Close film" }).click();
    await closed;
    const summary = page.locator("#film-transcript summary");
    await summary.focus();
    await page.keyboard.press("Enter");
    await expect(page.locator("#film-transcript li")).toHaveCount(9);
    await expect(page.locator("#film-transcript li").last()).toBeVisible();
});

test("currency examples support international decimals and localized output", async ({ page }) => {
    await page.goto("/");
    await page.getByLabel("Example currency").selectOption("EUR");
    await page.locator("#bill-amount").fill("160,50");
    await expect(page.locator('[data-person="0"]')).toHaveText("EUR 40.13");
    await page.getByLabel("Example currency").selectOption("JPY");
    await expect(page.locator("#bill-amount")).toHaveAttribute("aria-invalid", "true");
    await page.locator("#bill-amount").fill("161");
    await expect(page.locator('[data-person="0"]')).toHaveText("JPY 41");
    await page.getByLabel("Example currency").selectOption("KWD");
    await page.locator("#bill-amount").fill("160.125");
    await expect(page.locator('[data-person="0"]')).toHaveText("KWD 40.032");
    await expect(page.locator("#currency-help")).toContainText("not an exchange-rate conversion");
    expect(await page.locator("body").innerText()).not.toMatch(/paise|paisa/i);
});

test("new GitHub release updates every download field atomically", async ({ page }) => {
    const newer = {
        ...config.release,
        version: "2.3.4",
        tag: "v2.3.4",
        sizeBytes: 15000000,
        sha256: "a".repeat(64),
        apkUrl: `${config.repository}/releases/download/v2.3.4/SplitFree-v2.3.4.apk`,
        pageUrl: `${config.repository}/releases/tag/v2.3.4`,
    };
    let calls = 0;
    await page.route(RELEASE_API, (route) => {
        calls++;
        expect(route.request().method()).toBe("GET");
        expect(route.request().postData()).toBeNull();
        return route.fulfill({ json: releaseFixture(newer) });
    });
    await page.goto("/");
    await expect(page.locator("#release-status")).toHaveText(
        "Latest published release: v2.3.4. Checked with GitHub.",
    );
    for (const link of await page.locator("[data-download]").all())
        await expect(link).toHaveAttribute("href", newer.apkUrl);
    for (const node of await page.locator("[data-release-version]").all())
        await expect(node).toHaveText("v2.3.4");
    for (const node of await page.locator("[data-release-size]").all())
        await expect(node).toHaveText("15.0 MB");
    await expect(page.locator("[data-release-page]")).toHaveAttribute("href", newer.pageUrl);
    await expect(page.locator("#apk-checksum")).toHaveText(newer.sha256);
    await expect(page.locator("[data-release-filename]")).toHaveText("SplitFree-v2.3.4.apk");
    expect(calls).toBe(1);
});

test("API errors and hostile release metadata retain a clearly labelled fallback", async ({
    page,
}) => {
    for (const scenario of ["rate-limit", "hostile", "no-digest", "prerelease"]) {
        await page.route(RELEASE_API, (route) => {
            const data = releaseFixture(config.release);
            if (scenario === "hostile")
                data.assets[0].browser_download_url = "https://example.com/not-an-apk";
            if (scenario === "no-digest") data.assets[0].digest = null;
            if (scenario === "prerelease") data.prerelease = true;
            return route.fulfill(
                scenario === "rate-limit"
                    ? { status: 403, json: { message: "Rate limit" } }
                    : { json: data },
            );
        });
        await page.goto("/");
        await expect(page.locator("#release-status")).toContainText(
            "Live check unavailable. Showing bundled",
        );
        await expect(page.locator(".hero [data-download]")).toHaveAttribute(
            "href",
            config.release.apkUrl,
        );
        await expect(page.locator("#apk-checksum")).toHaveText(config.release.sha256);
        await expect(page.locator("#bill-amount")).toBeEnabled();
    }
});

test("release details stay pinned if a download starts during the live check", async ({ page }) => {
    let releaseResponse;
    const held = new Promise((resolve) => {
        releaseResponse = resolve;
    });
    const newer = {
        ...config.release,
        version: "2.3.4",
        tag: "v2.3.4",
        sizeBytes: 15000000,
        sha256: "b".repeat(64),
        apkUrl: `${config.repository}/releases/download/v2.3.4/SplitFree-v2.3.4.apk`,
        pageUrl: `${config.repository}/releases/tag/v2.3.4`,
    };
    await page.route(RELEASE_API, async (route) => {
        await held;
        await route.fulfill({ json: releaseFixture(newer) });
    });
    await page.goto("/");
    await expect(page.locator("#release-status")).toContainText("Checking");
    await page.locator(".hero [data-download]").dispatchEvent("pointerdown");
    releaseResponse();
    await expect(page.locator("#release-status")).toContainText("kept at");
    await expect(page.locator(".hero [data-download]")).toHaveAttribute(
        "href",
        config.release.apkUrl,
    );
    await expect(page.locator("#apk-checksum")).toHaveText(config.release.sha256);
});

test("compact footer keeps its real content visible at every responsive size", async ({ page }) => {
    for (const width of [320, 393, 768, 1280, 1920]) {
        await page.setViewportSize({ width, height: 900 });
        await page.goto("/");
        await page.evaluate(() => document.fonts.ready);
        await expect(page.locator(".footer-wordmark")).toHaveCount(0);
        await expect(page.locator(".footer-tagline")).toHaveText("Shared moments.Simpler money.");
        const geometry = await page.locator(".site-footer").evaluate((footer) => {
            const bounds = footer.getBoundingClientRect();
            return {
                height: bounds.height,
                width: footer.scrollWidth,
                outside: [...footer.querySelectorAll("a,p")].some((el) => {
                    const r = el.getBoundingClientRect();
                    return (
                        r.left < bounds.left ||
                        r.right > bounds.right + 1 ||
                        r.bottom > bounds.bottom + 1
                    );
                }),
            };
        });
        expect(geometry.height).toBeLessThan(width < 700 ? 520 : 390);
        expect(geometry.width).toBeLessThanOrEqual(width);
        expect(geometry.outside).toBe(false);
    }
});

for (const interaction of ["checksum disclosure", "manual checksum copy", "release page"]) {
    test(`pending release stays consistent after ${interaction}`, async ({ page }) => {
        let finish;
        const pending = new Promise((resolve) => {
            finish = resolve;
        });
        const newer = {
            ...config.release,
            version: "2.3.4",
            tag: "v2.3.4",
            sha256: "c".repeat(64),
            apkUrl: `${config.repository}/releases/download/v2.3.4/SplitFree-v2.3.4.apk`,
            pageUrl: `${config.repository}/releases/tag/v2.3.4`,
        };
        await page.route(RELEASE_API, async (route) => {
            await pending;
            await route.fulfill({ json: releaseFixture(newer) });
        });
        await page.goto("/");
        await expect(page.locator("#release-status")).toContainText("Checking");
        if (interaction === "checksum disclosure") {
            await page.locator(".checksum-details summary").focus();
            await page.keyboard.press("Enter");
            await expect(page.locator(".checksum-details")).toHaveAttribute("open", "");
        } else if (interaction === "manual checksum copy") {
            await page.locator(".checksum-details").evaluate((el) => {
                el.open = true;
            });
            await page.locator("#apk-checksum").dispatchEvent("pointerdown");
        } else {
            await page.locator("[data-release-page]").dispatchEvent("pointerdown");
        }
        finish();
        await expect(page.locator("#release-status")).toContainText(
            `kept at v${config.release.version}`,
        );
        for (const link of await page.locator("[data-download]").all())
            await expect(link).toHaveAttribute("href", config.release.apkUrl);
        await expect(page.locator("#apk-checksum")).toHaveText(config.release.sha256);
        await expect(page.locator("[data-release-page]")).toHaveAttribute(
            "href",
            config.release.pageUrl,
        );
    });
}

test("localized example results remain readable at 320px", async ({ browser }) => {
    const context = await browser.newContext({
        viewport: { width: 320, height: 800 },
        locale: "de-DE",
    });
    try {
        await context.route(STARS_API, (route) => route.fulfill({ json: starsFixture(42) }));
        await context.route(RELEASE_API, (route) =>
            route.fulfill({ json: releaseFixture(config.release) }),
        );
        const page = await context.newPage();
        await page.goto("http://127.0.0.1:4173/SplitFree/");
        await page.getByLabel("Example currency").selectOption("EUR");
        await page.locator("#bill-amount").fill("160,50");
        await expect(page.locator('[data-person="0"]')).toHaveText("40,13 EUR");
        for (const [currency, amount] of [
            ["KWD", "999999,999"],
            ["JPY", "999999"],
            ["KRW", "999999"],
            ["USD", "999999,99"],
        ]) {
            await page.getByLabel("Example currency").selectOption(currency);
            await page.locator("#bill-amount").fill(amount);
            await expect(page.locator("#amount-error")).toBeHidden();
            expect(
                await page.evaluate(() => document.documentElement.scrollWidth),
            ).toBeLessThanOrEqual(320);
            const clipped = await page
                .locator(".demo-person")
                .evaluateAll((rows) => rows.some((row) => row.scrollWidth > row.clientWidth + 1));
            expect(clipped).toBe(false);
        }
    } finally {
        await context.close();
    }
});

for (const count of [0, 99, 100, 101, 1234]) {
    test(`GitHub star count threshold ${count}`, async ({ page }) => {
        let calls = 0;
        await page.route(STARS_API, (route) => {
            calls++;
            expect(route.request().method()).toBe("GET");
            expect(route.request().postData()).toBeNull();
            expect(route.request().headers().authorization).toBeUndefined();
            expect(route.request().headers().cookie).toBeUndefined();
            expect(route.request().headers().referer).toBeUndefined();
            return route.fulfill({ json: starsFixture(count) });
        });
        await page.goto("/SplitFree/");
        await expect(page.locator("[data-star-label]")).toHaveText(starLabel(count));
        await expect(page.locator("[data-github-stars]")).toHaveAttribute(
            "href",
            config.repository,
        );
        await expect(page.locator("#release-status")).toContainText("Latest published release");
        expect(calls).toBe(1);
    });
}

test("star failures leave an ordinary link and do not block release or demo", async ({ page }) => {
    for (const scenario of ["offline", "limited", "invalid"]) {
        await page.route(STARS_API, (route) =>
            scenario === "offline"
                ? route.abort()
                : route.fulfill(
                      scenario === "limited"
                          ? { status: 429, json: { message: "Rate limited" } }
                          : { json: starsFixture(-1) },
                  ),
        );
        await page.goto("/");
        await expect(page.locator("[data-star-label]")).toHaveText("Star on GitHub");
        await expect(page.locator("#release-status")).toContainText("Latest published release");
        await expect(page.locator("#bill-amount")).toHaveValue("1600");
        await expect(page.locator("#bill-amount")).toBeEnabled();
        await expect(page.locator('[data-person="0"]')).toHaveText("₹400.00");
    }
});

test("no JavaScript retains a plain star link and the rupee example", async ({ browser }) => {
    const context = await browser.newContext({ javaScriptEnabled: false });
    try {
        const page = await context.newPage();
        await page.goto("http://127.0.0.1:4173/SplitFree/");
        await expect(page.locator("[data-star-label]")).toHaveText("Star on GitHub");
        await expect(page.locator("[data-github-stars]")).toHaveAttribute(
            "href",
            config.repository,
        );
        await expect(page.locator("#demo-currency")).toHaveValue("INR");
        await expect(page.locator("#bill-amount")).toHaveValue("1600");
        await expect(page.locator('[data-person="0"]')).toHaveText("₹400.00");
    } finally {
        await context.close();
    }
});

test("bill selection uses a shared focus underline without a box or layout jump", async ({
    page,
    browserName,
}) => {
    for (const width of [320, 393, 768, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        await page.goto("/SplitFree/");
        await page.evaluate(() => document.fonts.ready);
        const input = page.locator("#bill-amount");
        const baseline = page.locator(".amount-input");
        await page.getByLabel("Example currency").focus();
        const before = await baseline.boundingBox();
        await page.keyboard.press("Tab");
        await expect(input).toBeFocused();
        await expect(input).toHaveCSS("outline-style", "none");
        await input.evaluate((el) => el.select());
        const focus = await baseline.evaluate((el) => {
            const line = getComputedStyle(el, "::after");
            const field = el.querySelector("input");
            const symbol = el.querySelector("#currency-symbol").getBoundingClientRect();
            const rect = el.getBoundingClientRect();
            return {
                thickness: line.borderBottomWidth,
                style: line.borderBottomStyle,
                color: line.borderBottomColor,
                left: line.left,
                right: line.right,
                symbolInside: symbol.left >= rect.left && symbol.right <= rect.right,
                selection: field.value.slice(field.selectionStart, field.selectionEnd),
            };
        });
        expect(focus.thickness).toBe("3px");
        expect(focus.style).toBe("solid");
        expect(focus.color).toBe("rgb(45, 94, 69)");
        expect(focus.left).toBe("0px");
        expect(focus.right).toBe("0px");
        expect(focus.symbolInside).toBe(true);
        expect(focus.selection).toBe("1600");
        const after = await baseline.boundingBox();
        expect(after.width).toBe(before.width);
        expect(after.height).toBe(before.height);
        await input.fill("2000");
        await expect(page.locator('[data-person="0"]')).toHaveText("₹500.00");
        // Safari needs Option-Tab for non-text controls when Full Keyboard Access is off.
        await page.keyboard.press(browserName === "webkit" ? "Alt+Tab" : "Tab");
        await expect(page.getByLabel("Equal", { exact: true })).toBeFocused();
        expect(await baseline.evaluate((el) => getComputedStyle(el, "::after").content)).toBe(
            "none",
        );
        await input.click();
        await expect(input).toHaveCSS("outline-style", "none");
        expect(
            await baseline.evaluate((el) => getComputedStyle(el, "::after").borderBottomWidth),
        ).toBe("3px");
    }
});

test("mobile currency names fit and native controls keep full touch size", async ({ page }) => {
    for (const width of [320, 360, 393, 430]) {
        await page.setViewportSize({ width, height: 851 });
        await page.goto("/SplitFree/");
        await page.evaluate(() => document.fonts.ready);
        const select = page.getByLabel("Example currency");
        for (const currency of await select
            .locator("option")
            .evaluateAll((options) => options.map((option) => option.value))) {
            await select.selectOption(currency);
            const geometry = await select.evaluate((el) => {
                const style = getComputedStyle(el);
                const canvas = document.createElement("canvas");
                const context = canvas.getContext("2d");
                context.font = style.font;
                return {
                    height: el.getBoundingClientRect().height,
                    appearance: style.appearance,
                    available:
                        el.clientWidth -
                        parseFloat(style.paddingLeft) -
                        parseFloat(style.paddingRight),
                    text: context.measureText(el.selectedOptions[0].textContent).width,
                    font: parseFloat(style.fontSize),
                };
            });
            expect(geometry.height).toBeGreaterThanOrEqual(48);
            expect(geometry.appearance).toBe("none");
            expect(geometry.font).toBeGreaterThanOrEqual(16);
            expect(geometry.text, `${currency} clipped at ${width}`).toBeLessThanOrEqual(
                geometry.available + 1,
            );
        }
    }
});

test("mobile bill uses touch-safe styling and shows the entire maximum amount", async ({
    page,
    isMobile,
}) => {
    for (const width of [320, 360, 393, 430]) {
        await page.setViewportSize({ width, height: 851 });
        await page.goto("/SplitFree/");
        await page.evaluate(() => document.fonts.ready);
        const input = page.locator("#bill-amount");
        if (isMobile) await input.tap();
        else await input.click();
        await expect(input).toBeFocused();
        await expect(input).toHaveCSS("appearance", "none");
        await expect(input).toHaveCSS("border-radius", "0px");
        await expect(input).toHaveCSS("box-shadow", "none");
        await expect(input).toHaveCSS("outline-style", "none");
        await expect(input).toHaveAttribute("enterkeyhint", "done");
        for (const [currency, amount] of [
            ["INR", "999999.99"],
            ["KWD", "999999.999"],
            ["JPY", "999999"],
        ]) {
            await page.getByLabel("Example currency").selectOption(currency);
            await input.fill(amount);
            await expect(page.locator("#amount-error")).toBeHidden();
            const geometry = await input.evaluate((el) => {
                const style = getComputedStyle(el);
                const canvas = document.createElement("canvas");
                const context = canvas.getContext("2d");
                context.font = style.font;
                return {
                    available:
                        el.clientWidth -
                        parseFloat(style.paddingLeft) -
                        parseFloat(style.paddingRight),
                    text:
                        context.measureText(el.value).width +
                        (el.value.length - 1) * (parseFloat(style.letterSpacing) || 0),
                    scrollWidth: el.scrollWidth,
                    clientWidth: el.clientWidth,
                };
            });
            expect(geometry.text, `${currency} amount clipped at ${width}`).toBeLessThanOrEqual(
                geometry.available + 1,
            );
            expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.clientWidth + 1);
            expect(
                await page.evaluate(() => document.documentElement.scrollWidth),
            ).toBeLessThanOrEqual(width);
        }
        const url = page.url();
        await input.press("Enter");
        await expect(input).not.toBeFocused();
        expect(page.url()).toBe(url);
    }
});

test("closing or resizing mobile navigation never strands focus in hidden links", async ({
    page,
}) => {
    await page.setViewportSize({ width: 393, height: 851 });
    await page.goto("/");
    const menu = page.getByRole("button", { name: "Open menu" });
    await menu.click();
    await page.locator('#site-nav a[href="#how-it-works"]').focus();
    await page.keyboard.press("Enter");
    await expect(page.locator("#site-nav")).toBeHidden();
    await expect(page.locator("#how-it-works")).toBeFocused();
    await page.setViewportSize({ width: 1000, height: 700 });
    await page.locator('#site-nav a[href="#questions"]').focus();
    await page.setViewportSize({ width: 393, height: 851 });
    await expect(menu).toBeFocused();
    await page.setViewportSize({ width: 393, height: 250 });
    await menu.click();
    await page.locator('#site-nav a[href="#questions"]').scrollIntoViewIfNeeded();
    const box = await page.locator('#site-nav a[href="#questions"]').boundingBox();
    expect(box.y + box.height).toBeLessThanOrEqual(250);
});

test("mobile film popup keeps its close control reachable in a short viewport", async ({
    page,
    isMobile,
}) => {
    await page.setViewportSize({ width: 393, height: 600 });
    await page.goto("/");
    if (isMobile) await page.locator(".film-trigger").tap();
    else await page.locator(".film-trigger").click();
    await page.locator("dialog").evaluate((el) => {
        el.scrollTop = el.scrollHeight;
    });
    const box = await page.getByRole("button", { name: "Close film" }).boundingBox();
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.y + box.height).toBeLessThanOrEqual(600);
    const results = await new AxeBuilder({ page })
        .include("#film-dialog")
        .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
        .analyze();
    expect(
        results.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) })),
    ).toEqual([]);
    if (isMobile) await page.getByRole("button", { name: "Close film" }).tap();
    else await page.getByRole("button", { name: "Close film" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
});

test("social preview is available to crawlers without JavaScript and served as PNG", async ({
    browser,
    request,
}) => {
    const context = await browser.newContext({ javaScriptEnabled: false });
    try {
        const page = await context.newPage();
        await page.goto("http://127.0.0.1:4173/SplitFree/");
        await expect(page.locator('meta[name="twitter:card"]')).toHaveAttribute(
            "content",
            "summary_large_image",
        );
        const imageUrl = await page.locator('meta[property="og:image"]').getAttribute("content");
        expect(imageUrl).toBe("https://invincible04.github.io/SplitFree/assets/og-image.png");
        await expect(page.locator('meta[name="twitter:image"]')).toHaveAttribute(
            "content",
            imageUrl,
        );
        const imagePath = new URL(imageUrl).pathname;
        for (const localPath of [imagePath, imagePath.replace("/SplitFree", "")]) {
            const response = await request.get(localPath);
            expect(response.status()).toBe(200);
            expect(response.headers()["content-type"]).toBe("image/png");
            expect(await response.body()).toEqual(
                await readFile(new URL("../public/assets/og-image.png", import.meta.url)),
            );
            const head = await request.head(localPath);
            expect(head.status()).toBe(200);
            expect(head.headers()["content-type"]).toBe("image/png");
        }
        await page.goto(new URL(imagePath, page.url()).href);
        const image = page.locator("img");
        await expect(image).toBeVisible();
        expect(await image.evaluate((el) => [el.naturalWidth, el.naturalHeight])).toEqual([
            1200, 630,
        ]);
    } finally {
        await context.close();
    }
});
