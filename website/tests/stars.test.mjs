import test from "node:test";
import assert from "node:assert/strict";
import { STARS_API, parseStars, starLabel, fetchStars } from "../src/stars.js";

const repository = "https://github.com/invincible04/SplitFree";
const maxResponseBytes = 256 * 1024;
const encoder = new TextEncoder();

function repositoryData(patch = {}) {
    return {
        full_name: "invincible04/SplitFree",
        html_url: repository,
        private: false,
        stargazers_count: 100,
        ...patch,
    };
}

function jsonResponse(data = repositoryData(), init = {}) {
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

for (const [count, label] of [
    [0, "Star on GitHub"],
    [99, "Star on GitHub"],
    [100, "100 stars · Star on GitHub"],
    [101, "101 stars · Star on GitHub"],
    [999, "999 stars · Star on GitHub"],
    [1000, "1,000 stars · Star on GitHub"],
    [1234567, "1,234,567 stars · Star on GitHub"],
    [Number.MAX_SAFE_INTEGER, "9,007,199,254,740,991 stars · Star on GitHub"],
])
    test(`count ${count} parses and displays exactly without abbreviation or rounding`, () => {
        const data = repositoryData({ stargazers_count: count });
        const before = structuredClone(data);
        assert.equal(parseStars(data), count);
        assert.equal(starLabel(count), label);
        assert.deepEqual(data, before);
    });

for (const [label, count] of [
    ["missing", undefined],
    ["null", null],
    ["string", "100"],
    ["boolean", true],
    ["array", [100]],
    ["object", { count: 100 }],
    ["boxed number", new Number(100)],
    ["big integer", 100n],
    ["negative", -1],
    ["fraction", 99.9],
    ["NaN", NaN],
    ["infinity", Infinity],
    ["negative infinity", -Infinity],
    ["unsafe integer", Number.MAX_SAFE_INTEGER + 1],
])
    test(`parser and label reject invalid count: ${label}`, () => {
        assert.throws(() => parseStars(repositoryData({ stargazers_count: count })), RangeError);
        assert.throws(() => starLabel(count), RangeError);
    });

for (const [label, data] of [
    ["null", null],
    ["undefined", undefined],
    ["array", []],
    ["string", "repository"],
    ["number", 100],
    ["empty object", {}],
    ["missing name", repositoryData({ full_name: undefined })],
    ["other owner", repositoryData({ full_name: "other/SplitFree" })],
    ["other repository", repositoryData({ full_name: "invincible04/Other" })],
    ["name case change", repositoryData({ full_name: "invincible04/splitfree" })],
    ["name newline", repositoryData({ full_name: "invincible04/SplitFree\n" })],
    ["private repository", repositoryData({ private: true })],
    ["missing visibility", repositoryData({ private: undefined })],
    ["null visibility", repositoryData({ private: null })],
    ["string visibility", repositoryData({ private: "false" })],
    ["number visibility", repositoryData({ private: 0 })],
])
    test(`parser rejects invalid repository: ${label}`, () => {
        assert.throws(() => parseStars(data), /official public repository/);
    });

for (const url of [
    undefined,
    null,
    "http://github.com/invincible04/SplitFree",
    "https://github.com.evil.invalid/invincible04/SplitFree",
    "https://github.com@evil.invalid/invincible04/SplitFree",
    "https://github.com/other/SplitFree",
    "https://github.com/invincible04/Other",
    `${repository}/`,
    `${repository}?redirect=evil`,
    `${repository}#stars`,
    `${repository}\n`,
    ` ${repository}`,
])
    test(`parser rejects noncanonical repository URL ${JSON.stringify(url)}`, () => {
        assert.throws(
            () => parseStars(repositoryData({ html_url: url })),
            /official public repository/,
        );
    });

test("fetch makes exactly one fixed-origin credentialless GET and returns parsed metadata", async () => {
    let calls = 0;
    const result = await fetchStars(async (url, options) => {
        calls++;
        assert.equal(url, "https://api.github.com/repos/invincible04/SplitFree");
        assert.equal(url, STARS_API);
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
    assert.deepEqual(result, 100);
});

test("default fetch implementation can be mocked offline", async (t) => {
    const mock = t.mock.method(globalThis, "fetch", async () => jsonResponse());
    assert.equal(await fetchStars(), 100);
    assert.equal(mock.mock.callCount(), 1);
});

for (const status of [201, 202, 204, 206, 301, 302, 304, 401, 403, 404, 429, 500, 503])
    test(`HTTP ${status} fails closed without retries`, async () => {
        let calls = 0;
        await assert.rejects(
            fetchStars(async () => {
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
        fetchStars(async () => {
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
        jsonResponse(repositoryData(), { headers: {} }),
        Object.defineProperty(jsonResponse(), "redirected", { value: true }),
        Object.defineProperty(jsonResponse(), "url", { value: "https://evil.invalid/latest" }),
        jsonResponse(repositoryData(), { headers: { "Content-Type": "text/html" } }),
        new Response(null, { headers: { "Content-Type": "application/json" } }),
        new Response("{", { headers: { "Content-Type": "application/json" } }),
        jsonResponse(null),
        jsonResponse([]),
        jsonResponse({ message: "API rate limit exceeded" }),
        jsonResponse(repositoryData({ private: true })),
        jsonResponse(repositoryData({ stargazers_count: null })),
    ])
        await assert.rejects(fetchStars(async () => response));
});

test("streaming supports split UTF-8 characters and GitHub vendor JSON", async () => {
    const bytes = encoder.encode(JSON.stringify(repositoryData({ description: "₹" })));
    const start = bytes.indexOf(0xe2);
    const response = streamResponse([bytes.slice(0, start + 1), bytes.slice(start + 1)]);
    response.headers.set("Content-Type", "application/vnd.github+json; charset=utf-8");
    assert.equal(await fetchStars(async () => response), 100);
});

test("response limit is inclusive and counts streamed bytes rather than characters", async () => {
    const json = JSON.stringify(repositoryData());
    const exact = encoder.encode(json + " ".repeat(maxResponseBytes - encoder.encode(json).length));
    assert.equal(await fetchStars(async () => streamResponse([exact])), 100);
    for (const length of [undefined, 1, maxResponseBytes])
        await assert.rejects(
            fetchStars(async () => streamResponse([exact, encoder.encode(" ")], { length })),
            /size limit/,
        );
    const unicode = encoder.encode(
        JSON.stringify(repositoryData({ description: "₹".repeat(90000) })),
    );
    assert.ok(unicode.byteLength > maxResponseBytes);
    await assert.rejects(
        fetchStars(async () => streamResponse([unicode])),
        /size limit/,
    );
});

test("overlarge or invalid Content-Length is rejected before reading", async () => {
    for (const length of [maxResponseBytes + 1, "9007199254740992", "-1", "NaN", "1.5"])
        await assert.rejects(
            fetchStars(async () => streamResponse([], { length })),
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
        fetchStars(async () => response),
        /size limit/,
    );
    assert.equal(cancelled, 1);
    assert.equal(response.body.locked, false);
});

test("invalid UTF-8, nonbyte chunks and stream failures fail closed", async () => {
    for (const chunk of [new Uint8Array([0xff]), new Uint8Array([0xe2]), "not bytes"])
        await assert.rejects(fetchStars(async () => streamResponse([chunk])));
    await assert.rejects(
        fetchStars(
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
            fetchStars((_url, options) => {
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
            fetchStars(async () => response, 20),
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
            fetchStars(async () => response, 20),
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
        const result = fetchStars(async (_url, options) => {
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
            fetchStars(() => {
                calls++;
            }, timeout),
            RangeError,
        );
        assert.equal(calls, 0);
    }
    for (const fetch of [null, "https://evil.invalid/latest", {}])
        await assert.rejects(fetchStars(fetch), TypeError);
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
            const result = fetchStars(
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
