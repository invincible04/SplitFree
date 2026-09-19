import test from "node:test";
import { SECTION_INPUTS, assembleTemplate } from "../scripts/templates.mjs";
import assert from "node:assert/strict";
import { readFile, readdir, stat, mkdir, mkdtemp, rm, writeFile, symlink } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
    allocate,
    parseAmount,
    splitBill,
    examples,
    formatMoney,
    currencyDigits,
} from "../src/split.js";
import {
    validateConfig,
    renderHtml,
    build,
    validateTree,
    PUBLIC_INPUTS,
} from "../scripts/build.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const config = JSON.parse(await readFile(path.join(root, "site.config.json"), "utf8"));
for (const [input, expected] of [
    ["1600", 160000],
    [" 12.5 ", 1250],
    ["1", 100],
    ["999999.99", 99999999],
    ["1.01", 101],
    ["000001", 100],
])
    test(`parse precise amount ${input}`, () => assert.equal(parseAmount(input), expected));
for (const input of [
    "",
    "0",
    ".5",
    "-1",
    "NaN",
    "Infinity",
    "1e3",
    "1,600",
    "1000000",
    "1.234",
    "3.",
    "<script>",
    null,
    3,
])
    test(`reject malformed amount ${input}`, () => assert.equal(parseAmount(input), null));
test("equal and largest-remainder allocation preserve every minor unit", () => {
    assert.deepEqual(splitBill(160000, "equal"), [40000, 40000, 40000, 40000]);
    assert.deepEqual(splitBill(101, "equal"), [26, 25, 25, 25]);
});
test("all four illustrative modes have expected outcomes", () => {
    assert.deepEqual(splitBill(160000, "exact"), [50000, 50000, 30000, 30000]);
    assert.deepEqual(splitBill(160000, "percent"), [64000, 32000, 32000, 32000]);
    assert.deepEqual(splitBill(160000, "shares"), [64000, 32000, 32000, 32000]);
});
test("all modes preserve totals and integer minor units across edge cases", () => {
    for (const total of [0, 1, 2, 3, 99, 100, 101, 103, 160000, 99999999])
        for (const mode of Object.keys(examples)) {
            const values = splitBill(total, mode);
            assert.equal(
                values.reduce((a, b) => a + b, 0),
                total,
            );
            assert.ok(values.every((x) => Number.isSafeInteger(x) && x >= 0));
        }
    for (let total = 100; total < 100000; total += 113)
        for (const mode of Object.keys(examples))
            assert.equal(
                splitBill(total, mode).reduce((a, b) => a + b, 0),
                total,
            );
});
test("invalid allocation arguments and prototype keys fail closed", () => {
    for (const args of [
        [-1, [1]],
        [1.1, [1]],
        [Infinity, [1]],
        [1, []],
        [1, [0]],
        [1, [1.1]],
        [1, [NaN]],
        [1, [10001]],
        [1000000000, [1]],
    ])
        assert.throws(() => allocate(...args), RangeError);
    for (const mode of ["bad", "__proto__", "constructor"])
        assert.throws(() => splitBill(100, mode), RangeError);
});
test("currency decimals and international input", () => {
    assert.equal(currencyDigits("JPY"), 0);
    assert.equal(currencyDigits("KWD"), 3);
    assert.equal(parseAmount("160", "JPY"), 160);
    assert.equal(parseAmount("160.50", "JPY"), null);
    assert.equal(parseAmount("160,125", "KWD"), 160125);
    assert.equal(parseAmount("160,50", "EUR"), 16050);
    assert.equal(parseAmount("1,600.50", "USD"), null);
    assert.equal(parseAmount("999999.999", "KWD"), 999999999);
    assert.equal(formatMoney(1, "JPY"), "JPY 1");
    assert.equal(formatMoney(1, "KWD"), "KWD 0.001");
    assert.match(formatMoney(16050, "EUR", "de-DE"), /160,50/);
    assert.throws(() => currencyDigits("BOGUS"), RangeError);
});
test("currency formatting retains minor units", () => {
    assert.equal(formatMoney(160000), "₹1,600.00");
    assert.equal(formatMoney(101), "₹1.01");
});
test("release configuration is pinned to verified official assets", () => {
    assert.equal(validateConfig(config), config);
    assert.equal(
        config.release.apkUrl,
        `${config.repository}/releases/download/${config.release.tag}/SplitFree-${config.release.tag}.apk`,
    );
    assert.equal(config.release.sha256.length, 64);
});
test("unsafe or mismatched release configuration is rejected", () => {
    for (const patch of [
        { apkUrl: "javascript:alert(1)" },
        { apkUrl: config.release.apkUrl.replace("github.com", "example.com") },
        { pageUrl: "https://example.com" },
        { version: `${config.release.version}.invalid` },
        { sha256: "bad" },
        { sizeBytes: -1 },
        { tag: "not-a-release-tag" },
    ])
        assert.throws(() =>
            validateConfig({ ...config, release: { ...config.release, ...patch } }),
        );
    assert.throws(() => validateConfig({ ...config, repository: "https://example.com" }));
});
test("unknown template expressions cannot ship silently", () => {
    assert.throws(() => renderHtml("{{missing}}", config));
    assert.throws(() => renderHtml("{{bad-key}}", config));
    assert.equal(
        renderHtml("{{version}} / {{size}}", config),
        `${config.release.version} / ${(config.release.sizeBytes / 1e6).toFixed(1)}`,
    );
});
test("production build is standalone and contains only public artifacts", async () => {
    const dist = await build();
    const html = await readFile(path.join(dist, "index.html"), "utf8");
    assert.ok(!html.includes("{{") && !html.includes("include:"));
    assert.equal((html.match(/data-download/g) || []).length, 3);
    assert.ok(html.includes(config.release.apkUrl));
    assert.ok(!/(?:src|href)="\/(?!\/)/.test(html));
    assert.ok(!/<script[^>]+src="https?:/.test(html));
    const files = [];
    async function walk(dir) {
        for (const entry of await readdir(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) await walk(full);
            else files.push(path.relative(dist, full));
        }
    }
    await walk(dist);
    for (const file of files)
        assert.ok(
            !/node_modules|sections|site\.config|package|tests|scripts|\.apk$|\.jks$|\.env|\.map$/.test(
                file,
            ),
            file,
        );
    for (const match of html.matchAll(/(?:src|href|poster|data-src)="([^"#]+)"/g)) {
        const ref = match[1];
        if (/^[a-z]+:/i.test(ref)) continue;
        const info = await stat(path.join(dist, ref));
        assert.ok(info.isFile(), ref);
    }
    for (const match of html.matchAll(/href="#([^"]+)"/g))
        assert.ok(html.includes(`id="${match[1]}"`), `Missing anchor: ${match[1]}`);
    assert.ok((await stat(path.join(dist, "assets/intro.mp4"))).size < 12e6);
    assert.ok((await stat(path.join(dist, "src/main.js"))).size < 15000);
});

test("build rejects hidden files, unknown extensions and symlink inputs", async () => {
    const evidence = path.join(root, "evidence");
    await mkdir(evidence, { recursive: true });
    const temp = await mkdtemp(path.join(evidence, "build-guard-"));
    try {
        const allowed = new Set([".js"]);
        await writeFile(path.join(temp, "safe.js"), "export const value = 1;");
        await validateTree(temp, allowed);
        await writeFile(path.join(temp, "signing-password.txt"), "synthetic non-secret fixture");
        await assert.rejects(
            validateTree(temp, new Set([".js", ".txt"]), new Set(["safe.js"])),
            /Unreviewed build input/,
        );
        await rm(path.join(temp, "signing-password.txt"));
        for (const name of [".hidden.js", "unknown.bin"]) {
            await writeFile(path.join(temp, name), "not publishable");
            await assert.rejects(validateTree(temp, allowed));
            await rm(path.join(temp, name));
        }
        await symlink(path.join(temp, "safe.js"), path.join(temp, "linked.js"));
        await assert.rejects(validateTree(temp, allowed), /Symbolic build input/);
        await rm(path.join(temp, "linked.js"));
        await mkdir(path.join(temp, "nested"));
        await writeFile(path.join(temp, "nested", "footer.html"), "unexpected nested section");
        await assert.rejects(
            validateTree(temp, new Set([".js", ".html"]), new Set(["safe.js", "footer.html"])),
            /Unreviewed build input/,
        );
        await rm(path.join(temp, "nested", "footer.html"));
        await symlink(path.join(temp, "nested"), path.join(temp, "linked-directory"));
        await assert.rejects(
            validateTree(path.join(temp, "linked-directory"), allowed),
            /Invalid build input/,
        );
    } finally {
        await rm(temp, { recursive: true, force: true });
    }
});

test("Pages deploy is push-or-manual, mainline-only, pinned, and isolated from APK signing", async () => {
    const workflow = await readFile(path.join(root, "../.github/workflows/pages.yml"), "utf8");
    assert.match(workflow, /workflow_dispatch:/);
    assert.match(
        workflow,
        /on:\n  push:\n    branches: \[mainline\]\n  pull_request:\n    branches: \[mainline\]/,
    );
    assert.doesNotMatch(workflow, /pull_request_target:|paths:/);
    assert.doesNotMatch(workflow, /secrets\.|contents: write|write-all/);
    for (const action of workflow.matchAll(/uses: ([^\s]+)/g))
        assert.match(action[1], /@[a-f0-9]{40}$/);
    assert.match(workflow, /persist-credentials: false/);
    assert.match(workflow, /--directory dist -cf/);
    assert.match(workflow, /name: github-pages/);
    assert.doesNotMatch(workflow, /upload-pages-artifact/);
    assert.match(
        workflow,
        /if: \(github.event_name == 'push' \|\| github.event_name == 'workflow_dispatch'\) && github.ref == 'refs\/heads\/mainline' && github.repository == 'invincible04\/SplitFree'/,
    );
    assert.match(workflow, /needs: check/);
    assert.match(workflow, /run: npm run check/);
    assert.match(workflow, /pages: write/);
    assert.match(workflow, /id-token: write/);
});

test("license and notice files accompany the deployable site", async () => {
    assert.equal(
        await readFile(path.join(root, "dist/LICENSE.txt"), "utf8"),
        await readFile(path.join(root, "../LICENSE"), "utf8"),
    );
    assert.equal(
        await readFile(path.join(root, "dist/THIRD-PARTY-NOTICES.txt"), "utf8"),
        await readFile(path.join(root, "THIRD_PARTY_NOTICES.md"), "utf8"),
    );
    for (const name of ["Inter-OFL.txt", "Manrope-OFL.txt", "Apache-2.0.txt"])
        assert.equal(
            await readFile(path.join(root, "dist/licenses", name), "utf8"),
            await readFile(path.join(root, "public/licenses", name), "utf8"),
        );
    const html = await readFile(path.join(root, "dist/index.html"), "utf8");
    const footer = html.match(/<footer[\s\S]*?<\/footer>/)[0];
    assert.match(footer, /href="LICENSE\.txt"/);
    assert.match(footer, /href="THIRD-PARTY-NOTICES\.txt"/);
});

test("public inputs are an exact manifest, never a directory wildcard", () => {
    assert.ok(PUBLIC_INPUTS.has("licenses/Apache-2.0.txt"));
    assert.ok(!PUBLIC_INPUTS.has("signing-password.txt"));
    assert.ok(!PUBLIC_INPUTS.has("assets/another-film.mp4"));
});

test("section assembly supports known nested includes without browser requests", () => {
    assert.equal(
        assembleTemplate("A<!-- include: hero.html -->Z", {
            "hero.html": "B<!-- include: split-demo.html -->",
            "split-demo.html": "C",
        }),
        "ABCZ",
    );
    assert.equal(SECTION_INPUTS.size, 13);
});

test("section assembly rejects missing, duplicate, circular and unsafe includes", () => {
    for (const name of ["../LICENSE", "hero.html", "extra.html", "/etc/passwd"])
        assert.throws(() => assembleTemplate(`<!-- include: ${name} -->`, {}), /Unknown section/);
    assert.throws(
        () =>
            assembleTemplate("<!-- include: hero.html --><!-- include: hero.html -->", {
                "hero.html": "text",
            }),
        /Duplicate/,
    );
    assert.throws(
        () =>
            assembleTemplate("<!-- include: hero.html -->", {
                "hero.html": "<!-- include: hero.html -->",
            }),
        /Circular/,
    );
    assert.throws(() => assembleTemplate("<!-- include hero.html -->", {}), /Malformed/);
    assert.throws(() => assembleTemplate("", { "hero.html": "text" }), /Unused/);
    assert.throws(() => assembleTemplate("", { "extra.html": "text" }), /Unreviewed/);
});

test("page source is a small shell and all section files stay manageable", async () => {
    assert.ok((await readFile(path.join(root, "index.html"), "utf8")).split("\n").length <= 40);
    for (const name of SECTION_INPUTS)
        assert.ok(
            (await readFile(path.join(root, "sections", name), "utf8")).split("\n").length <= 240,
            name,
        );
});

test("hero totals and default rupee demo agree", async () => {
    const html = await readFile(path.join(root, "dist/index.html"), "utf8");
    assert.match(html, /TOTAL SHARED/);
    assert.match(html, /₹8,400/);
    for (const amount of ["₹1,600", "₹6,000", "₹800", "₹400"]) assert.ok(html.includes(amount));
    assert.equal(1600 + 6000 + 800, 8400);
    assert.deepEqual(splitBill(parseAmount("1600"), "equal"), [40000, 40000, 40000, 40000]);
    assert.doesNotMatch(html, /US\$|YOU ARE OWED/);
});
