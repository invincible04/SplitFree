import test from "node:test";
import assert from "node:assert/strict";
import { RELEASE_API, parseRelease, fetchLatestRelease } from "../src/release.js";

const repository = "https://github.com/invincible04/SplitFree";
const digest = "e1f1fea669027c6ac4c03f4695c1af60e28de2c3f14ed24afc61b171c8b6c0fe";
const maxResponseBytes = 256 * 1024;
const encoder = new TextEncoder();

function asset(name, patch = {}, tag = "v1.0.0") {
    return {
        name,
        browser_download_url: `${repository}/releases/download/${tag}/${name}`,
        state: "uploaded",
        size: 11925034,
        digest: `sha256:${digest}`,
        content_type: "application/octet-stream",
        ...patch,
    };
}

function release(patch = {}) {
    return {
        tag_name: "v1.0.0",
        html_url: `${repository}/releases/tag/v1.0.0`,
        draft: false,
        prerelease: false,
        immutable: false,
        assets: [
            asset("LICENSE.txt"),
            asset("SplitFree-v1.0.0.apk", {
                content_type: "application/vnd.android.package-archive",
            }),
            asset("SHA256SUMS.txt"),
            asset("SplitFree-v1.0.0-source.tar.gz"),
        ],
        ...patch,
    };
}

function withApk(patch) {
    const data = release();
    Object.assign(data.assets[1], patch);
    return data;
}

function jsonResponse(data = release(), init = {}) {
    return new Response(JSON.stringify(data), {
        headers: { "Content-Type": "application/json; charset=utf-8" },
        ...init,
    });
}

function streamResponse(chunks, { length, cancel = () => {} } = {}) {
    const headers = { "Content-Type": "application/json" };
    if (length !== undefined) headers["Content-Length"] = String(length);
    return new Response(
        new ReadableStream({
            start(controller) {
                for (const chunk of chunks) controller.enqueue(chunk);
                controller.close();
            },
            cancel,
        }),
        { headers },
    );
}

test("parser accepts the live release shape without requiring immutability or mutating input", () => {
    const data = release();
    const before = structuredClone(data);
    const today = new Date().toISOString().slice(0, 10);
    assert.deepEqual(parseRelease(data), {
        version: "1.0.0",
        tag: "v1.0.0",
        apkUrl: `${repository}/releases/download/v1.0.0/SplitFree-v1.0.0.apk`,
        pageUrl: `${repository}/releases/tag/v1.0.0`,
        sizeBytes: 11925034,
        sha256: digest,
        verifiedAt: today,
    });
    assert.deepEqual(data, before);
    delete data.immutable;
    assert.equal(parseRelease(data).version, "1.0.0");
});

test("parser supports other stable versions and canonicalizes GitHub-declared hexadecimal", () => {
    for (const tag of ["v0.0.0", "v2.10.30", "v10.100.1000"]) {
        const data = release({
            tag_name: tag,
            html_url: `${repository}/releases/tag/${tag}`,
            assets: [
                asset(`SplitFree-${tag}.apk`, { digest: `sha256:${digest.toUpperCase()}` }, tag),
            ],
        });
        assert.equal(parseRelease(data).version, tag.slice(1));
        assert.equal(parseRelease(data).sha256, digest);
    }
    assert.equal(parseRelease(withApk({ size: 1 })).sizeBytes, 1);
    assert.equal(parseRelease(withApk({ size: 512 * 1024 * 1024 })).sizeBytes, 512 * 1024 * 1024);
});

for (const [label, data] of [
    ["null", null],
    ["array", []],
    ["string", "release"],
    ["number", 1],
    ["empty object", {}],
    ["draft", release({ draft: true })],
    ["prerelease", release({ prerelease: true })],
    ["missing draft", release({ draft: undefined })],
    ["missing prerelease", release({ prerelease: undefined })],
    ["false string", release({ prerelease: "false" })],
    ["false number", release({ draft: 0 })],
    ["missing assets", release({ assets: undefined })],
    ["empty assets", release({ assets: [] })],
    ["object assets", release({ assets: {} })],
    ["null asset", release({ assets: [null] })],
    ["no APK", release({ assets: [asset("LICENSE.txt")] })],
])
    test(`parser rejects malformed or non-stable release: ${label}`, () => {
        assert.throws(() => parseRelease(data));
    });

for (const tag of [
    "1.0.0",
    "V1.0.0",
    "v1.0",
    "v1.0.0.0",
    "v01.0.0",
    "v1.01.0",
    "v1.0.01",
    "v1.0.0-beta",
    "v1.0.0+build",
    "v1.0.0\n",
    " v1.0.0",
    "v1.0.0 ",
    "v1.0.0/../../other",
    `v${"1".repeat(130)}.0.0`,
    100,
    null,
])
    test(`parser rejects noncanonical semver ${JSON.stringify(tag)}`, () => {
        assert.throws(() => parseRelease(release({ tag_name: tag })), /release tag/);
    });

for (const url of [
    "javascript:alert(1)",
    "http://github.com/invincible04/SplitFree/releases/tag/v1.0.0",
    "https://github.com.evil.invalid/invincible04/SplitFree/releases/tag/v1.0.0",
    "https://github.com@evil.invalid/invincible04/SplitFree/releases/tag/v1.0.0",
    "https://github.com/invincible04/Other/releases/tag/v1.0.0",
    "https://github.com/invincible04/SplitFree/releases/tag/v2.0.0",
    `${repository}/releases/tag/v1.0.0?redirect=evil`,
    `${repository}/releases/tag/v1.0.0#download`,
    `${repository}/releases/tag/v1.0.0\n`,
    null,
])
    test(`parser rejects unsafe release page ${JSON.stringify(url)}`, () => {
        assert.throws(() => parseRelease(release({ html_url: url })), /release URL/);
    });

for (const [label, patch] of [
    ["wrong name", { name: "other.apk" }],
    ["uppercase name", { name: "SplitFree-v1.0.0.APK" }],
    ["HTML name", { name: '<img src=x onerror="alert(1)">.apk' }],
    ["encoded path", { name: "..%2FSplitFree-v1.0.0.apk" }],
    ["padded extension", { name: "SplitFree-v1.0.0.apk " }],
    ["control character", { name: "SplitFree-v1.0.0.apk\n" }],
    ["non-string name", { name: 1 }],
    ["unuploaded", { state: "starter" }],
    ["missing state", { state: undefined }],
    ["string size", { size: "11925034" }],
    ["zero size", { size: 0 }],
    ["negative size", { size: -1 }],
    ["fractional size", { size: 1.5 }],
    ["NaN size", { size: NaN }],
    ["infinite size", { size: Infinity }],
    ["oversized APK", { size: 512 * 1024 * 1024 + 1 }],
    ["unsafe integer", { size: Number.MAX_SAFE_INTEGER + 1 }],
    ["missing digest", { digest: undefined }],
    ["null digest", { digest: null }],
    ["unprefixed digest", { digest }],
    ["wrong algorithm", { digest: `sha512:${digest}` }],
    ["wrong length", { digest: `sha256:${digest.slice(1)}` }],
    ["nonhex digest", { digest: `sha256:${"g".repeat(64)}` }],
    ["digest newline", { digest: `sha256:${digest}\n` }],
])
    test(`parser rejects invalid APK metadata: ${label}`, () => {
        assert.throws(() => parseRelease(withApk(patch)));
    });

for (const url of [
    "javascript:alert(1)",
    `http://github.com/invincible04/SplitFree/releases/download/v1.0.0/SplitFree-v1.0.0.apk`,
    `https://github.com.evil.invalid/invincible04/SplitFree/releases/download/v1.0.0/SplitFree-v1.0.0.apk`,
    `https://github.com@evil.invalid/invincible04/SplitFree/releases/download/v1.0.0/SplitFree-v1.0.0.apk`,
    `${repository}/releases/download/v2.0.0/SplitFree-v2.0.0.apk`,
    `${repository}/releases/download/v1.0.0/%53plitFree-v1.0.0.apk`,
    `${repository}/releases/download/v1.0.0/SplitFree-v1.0.0.apk?download=1`,
    `${repository}/releases/download/v1.0.0/SplitFree-v1.0.0.apk#fragment`,
    `${repository}/releases/download/v1.0.0/../v1.0.0/SplitFree-v1.0.0.apk`,
    null,
])
    test(`parser rejects unsafe APK URL ${JSON.stringify(url)}`, () => {
        assert.throws(() => parseRelease(withApk({ browser_download_url: url })), /release asset/);
    });

test("parser rejects duplicate assets, alternate APKs, and name/type/URL ambiguities", () => {
    for (const extra of [
        asset("SplitFree-v1.0.0.apk"),
        asset("other.apk"),
        asset("other.APK"),
        asset("other.apk "),
        asset("other.txt", { content_type: "application/vnd.android.package-archive" }),
        asset("other.txt", { browser_download_url: release().assets[1].browser_download_url }),
        asset("LICENSE.txt"),
        asset("license.TXT"),
    ]) {
        const data = release();
        data.assets.push(extra);
        assert.throws(() => parseRelease(data), undefined, extra.name);
    }
});

test("fetch makes exactly one fixed-origin credentialless GET and returns parsed metadata", async () => {
    let calls = 0;
    const result = await fetchLatestRelease(async (url, options) => {
        calls++;
        assert.equal(url, "https://api.github.com/repos/invincible04/SplitFree/releases/latest");
        assert.equal(url, RELEASE_API);
        assert.deepEqual(options, {
            method: "GET",
            headers: { Accept: "application/vnd.github+json" },
            credentials: "omit",
            mode: "cors",
            cache: "no-store",
            redirect: "error",
            referrerPolicy: "no-referrer",
            signal: options.signal,
        });
        assert.ok(options.signal instanceof AbortSignal);
        assert.equal(options.signal.aborted, false);
        return jsonResponse();
    });
    assert.equal(calls, 1);
    assert.deepEqual(result, parseRelease(release()));
});

test("default fetch implementation can be mocked offline", async (t) => {
    const mock = t.mock.method(globalThis, "fetch", async () => jsonResponse());
    assert.equal((await fetchLatestRelease()).tag, "v1.0.0");
    assert.equal(mock.mock.callCount(), 1);
});

for (const status of [204, 206, 301, 302, 304, 401, 403, 404, 429, 500, 503])
    test(`HTTP ${status} fails closed without retries`, async () => {
        let calls = 0;
        await assert.rejects(
            fetchLatestRelease(async () => {
                calls++;
                return new Response(null, { status });
            }),
            new RegExp(`HTTP ${status}`),
        );
        assert.equal(calls, 1);
    });

test("network errors propagate without retries", async () => {
    let calls = 0;
    const error = new TypeError("Offline");
    await assert.rejects(
        fetchLatestRelease(async () => {
            calls++;
            throw error;
        }),
        (actual) => actual === error,
    );
    assert.equal(calls, 1);
});

test("response URL, media type, stream, JSON syntax, and JSON shape are validated", async () => {
    for (const response of [
        null,
        { ok: true, status: 200 },
        jsonResponse(release(), { headers: {} }),
        Object.defineProperty(jsonResponse(), "redirected", { value: true }),
        Object.defineProperty(jsonResponse(), "url", { value: "https://evil.invalid/latest" }),
        jsonResponse(release(), { headers: { "Content-Type": "text/html" } }),
        new Response(null, { headers: { "Content-Type": "application/json" } }),
        new Response("{", { headers: { "Content-Type": "application/json" } }),
        jsonResponse(null),
        jsonResponse([]),
        jsonResponse({ message: "API rate limit exceeded" }),
        jsonResponse(release({ prerelease: true })),
        jsonResponse(withApk({ digest: null })),
    ])
        await assert.rejects(fetchLatestRelease(async () => response));
});

test("streaming supports split UTF-8 characters and GitHub vendor JSON", async () => {
    const bytes = encoder.encode(JSON.stringify(release({ body: "₹" })));
    const start = bytes.indexOf(0xe2);
    const response = streamResponse([bytes.slice(0, start + 1), bytes.slice(start + 1)]);
    response.headers.set("Content-Type", "application/vnd.github+json; charset=utf-8");
    assert.equal((await fetchLatestRelease(async () => response)).sha256, digest);
});

test("response limit is inclusive and counts streamed bytes rather than characters", async () => {
    const json = JSON.stringify(release());
    const exact = encoder.encode(json + " ".repeat(maxResponseBytes - encoder.encode(json).length));
    assert.equal((await fetchLatestRelease(async () => streamResponse([exact]))).version, "1.0.0");
    for (const length of [undefined, 1, maxResponseBytes])
        await assert.rejects(
            fetchLatestRelease(async () =>
                streamResponse([exact, encoder.encode(" ")], { length }),
            ),
            /size limit/,
        );
    const unicode = encoder.encode(JSON.stringify(release({ body: "₹".repeat(90000) })));
    assert.ok(unicode.byteLength > maxResponseBytes);
    await assert.rejects(
        fetchLatestRelease(async () => streamResponse([unicode])),
        /size limit/,
    );
});

test("overlarge or invalid Content-Length is rejected before reading", async () => {
    for (const length of [maxResponseBytes + 1, "9007199254740992", "-1", "NaN", "1.5"])
        await assert.rejects(
            fetchLatestRelease(async () => streamResponse([], { length })),
            /size limit/,
        );
});

test("oversized streams are cancelled without consuming later chunks", async () => {
    let cancelled = 0;
    const response = streamResponse(
        [new Uint8Array(maxResponseBytes + 1), encoder.encode("unread")],
        {
            cancel: () => {
                cancelled++;
            },
        },
    );
    await assert.rejects(
        fetchLatestRelease(async () => response),
        /size limit/,
    );
    assert.equal(cancelled, 1);
    assert.equal(response.body.locked, false);
});

test("invalid UTF-8, nonbyte chunks and stream failures fail closed", async () => {
    for (const chunk of [new Uint8Array([0xff]), new Uint8Array([0xe2]), "not bytes"])
        await assert.rejects(fetchLatestRelease(async () => streamResponse([chunk])));
    await assert.rejects(
        fetchLatestRelease(
            async () =>
                new Response(
                    new ReadableStream({
                        start(controller) {
                            controller.error(new Error("Broken stream"));
                        },
                    }),
                    { headers: { "Content-Type": "application/json" } },
                ),
        ),
        /Broken stream/,
    );
});

test(
    "deadline rejects and aborts even when fetch ignores the signal",
    { timeout: 1000 },
    async () => {
        let signal;
        let calls = 0;
        const start = performance.now();
        await assert.rejects(
            fetchLatestRelease((_url, options) => {
                calls++;
                signal = options.signal;
                return new Promise(() => {});
            }, 20),
            { name: "TimeoutError" },
        );
        assert.equal(calls, 1);
        assert.equal(signal.aborted, true);
        assert.ok(performance.now() - start < 500);
    },
);

test(
    "deadline also cancels a stalled response body when fetch has already returned",
    { timeout: 1000 },
    async () => {
        let cancelled = false;
        const response = new Response(
            new ReadableStream({
                cancel() {
                    cancelled = true;
                },
            }),
            { headers: { "Content-Type": "application/json" } },
        );
        await assert.rejects(
            fetchLatestRelease(async () => response, 20),
            { name: "TimeoutError" },
        );
        assert.equal(cancelled, true);
        assert.equal(response.body.locked, false);
    },
);

test(
    "cancel failures do not mask validation errors or stall a timeout",
    { timeout: 1000 },
    async () => {
        const response = new Response(
            new ReadableStream({
                cancel() {
                    return Promise.reject(new Error("Cancel failed"));
                },
            }),
            { headers: { "Content-Type": "application/json" } },
        );
        await assert.rejects(
            fetchLatestRelease(async () => response, 20),
            { name: "TimeoutError" },
        );
    },
);

test("deadline timer is cleared after success and failure", async (t) => {
    const timers = new Map();
    const deadlines = [];
    t.mock.method(globalThis, "setTimeout", (callback, milliseconds) => {
        const timer = Symbol("timer");
        timers.set(timer, callback);
        deadlines.push(milliseconds);
        return timer;
    });
    t.mock.method(globalThis, "clearTimeout", (timer) => timers.delete(timer));
    for (const fails of [false, true]) {
        let signal;
        let aborts = 0;
        const result = fetchLatestRelease(async (_url, options) => {
            signal = options.signal;
            signal.addEventListener("abort", () => {
                aborts++;
            });
            if (fails) throw new Error("Offline");
            return jsonResponse();
        });
        if (fails) await assert.rejects(result, /Offline/);
        else await result;
        assert.equal(timers.size, 0);
        assert.equal(signal.aborted, fails);
        assert.equal(aborts, fails ? 1 : 0);
    }
    assert.deepEqual(deadlines, [5000, 5000]);
});

test("invalid timeout or fetch arguments cannot choose another URL or initiate a request", async () => {
    for (const timeout of [0, -1, 1.5, NaN, Infinity, "5000", 2147483648]) {
        let calls = 0;
        await assert.rejects(
            fetchLatestRelease(() => {
                calls++;
            }, timeout),
            RangeError,
        );
        assert.equal(calls, 0);
    }
    for (const fetch of [null, "https://evil.invalid/latest", {}])
        await assert.rejects(fetchLatestRelease(fetch), TypeError);
});

test(
    "rejected headers and late fetch results release unread response bodies",
    { timeout: 1000 },
    async () => {
        for (const late of [false, true]) {
            let cancelled = 0;
            let resolveFetch;
            const response = new Response(
                new ReadableStream({
                    cancel() {
                        cancelled++;
                    },
                }),
                {
                    headers: {
                        "Content-Type": "application/json",
                        "Content-Length": String(maxResponseBytes + 1),
                    },
                },
            );
            const result = fetchLatestRelease(
                () =>
                    late
                        ? new Promise((resolve) => {
                              resolveFetch = resolve;
                          })
                        : response,
                20,
            );
            if (late) {
                await assert.rejects(result, { name: "TimeoutError" });
                resolveFetch(response);
                await Promise.resolve();
            } else {
                await assert.rejects(result, /size limit/);
            }
            assert.equal(cancelled, 1);
            assert.equal(response.body.locked, false);
        }
    },
);
