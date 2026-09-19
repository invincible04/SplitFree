export const STARS_API = "https://api.github.com/repos/invincible04/SplitFree";

const REPOSITORY_NAME = "invincible04/SplitFree";
const REPOSITORY = "https://github.com/invincible04/SplitFree";
const MAX_RESPONSE_BYTES = 256 * 1024;
const starFormatter = new Intl.NumberFormat("en-US");

function validateCount(count) {
    if (!Number.isSafeInteger(count) || count < 0) throw new RangeError("Invalid star count");
}

export function parseStars(data) {
    if (
        data === null ||
        typeof data !== "object" ||
        Array.isArray(data) ||
        data.full_name !== REPOSITORY_NAME ||
        data.html_url !== REPOSITORY ||
        data.private !== false
    )
        throw new Error("Expected the official public repository");
    validateCount(data.stargazers_count);
    return data.stargazers_count;
}

export function starLabel(count) {
    validateCount(count);
    return count < 100 ? "Star on GitHub" : `${starFormatter.format(count)} stars · Star on GitHub`;
}

function validateResponse(response) {
    if (!response || response.ok !== true || response.status !== 200)
        throw new Error(`Stars request failed (HTTP ${response?.status ?? "unknown"})`);
    if (response.redirected || (response.url && response.url !== STARS_API))
        throw new Error("Unexpected stars response URL");
    const contentType = response.headers?.get("content-type") ?? "";
    if (!/^application\/(?:json|vnd\.github\+json)(?:\s*;|$)/i.test(contentType))
        throw new Error("Expected a JSON stars response");
    const length = response.headers.get("content-length");
    if (length !== null && (!/^\d+$/.test(length) || Number(length) > MAX_RESPONSE_BYTES))
        throw new Error("Stars response exceeds the size limit or has an invalid length");
    if (typeof response.body?.getReader !== "function")
        throw new Error("Missing readable stars response");
}

async function readStarsJson(response, signal) {
    const reader = response.body.getReader();
    const cancel = () => {
        // Cleanup failure must not replace the validation error or extend the request deadline.
        void reader.cancel(signal.reason).catch(() => {});
    };
    signal.addEventListener("abort", cancel, { once: true });
    try {
        const decoder = new TextDecoder("utf-8", { fatal: true });
        let size = 0;
        let text = "";
        while (true) {
            signal.throwIfAborted();
            const { done, value } = await reader.read();
            signal.throwIfAborted();
            if (done) break;
            if (!(value instanceof Uint8Array)) throw new Error("Invalid stars response bytes");
            size += value.byteLength;
            if (size > MAX_RESPONSE_BYTES) throw new Error("Stars response exceeds the size limit");
            text += decoder.decode(value, { stream: true });
        }
        return JSON.parse(text + decoder.decode());
    } finally {
        signal.removeEventListener("abort", cancel);
        cancel();
        reader.releaseLock();
    }
}

async function requestStars(fetchImpl, signal) {
    const response = await fetchImpl(STARS_API, {
        method: "GET",
        headers: { Accept: "application/vnd.github+json" },
        credentials: "omit",
        mode: "cors",
        cache: "no-store",
        redirect: "error",
        referrerPolicy: "no-referrer",
        signal,
    });
    try {
        signal.throwIfAborted();
        validateResponse(response);
        return parseStars(await readStarsJson(response, signal));
    } finally {
        // Also release unread bodies rejected by headers or returned after a mocked fetch times out.
        if (response?.body && !response.body.locked)
            void response.body.cancel(signal.reason).catch(() => {});
    }
}

export async function fetchStars(fetchImpl = globalThis.fetch, timeoutMs = 5000) {
    if (typeof fetchImpl !== "function") throw new TypeError("Fetch is unavailable");
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 2147483647)
        throw new RangeError("Invalid stars request timeout");
    const controller = new AbortController();
    const timeoutError = new DOMException("Stars request timed out", "TimeoutError");
    let timer;
    const deadline = new Promise((_, reject) => {
        timer = setTimeout(() => {
            reject(timeoutError);
            controller.abort(timeoutError);
        }, timeoutMs);
    });
    try {
        return await Promise.race([requestStars(fetchImpl, controller.signal), deadline]);
    } catch (error) {
        controller.abort(error);
        throw error;
    } finally {
        clearTimeout(timer);
    }
}
