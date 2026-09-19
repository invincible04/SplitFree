export const RELEASE_API = "https://api.github.com/repos/invincible04/SplitFree/releases/latest";

const REPOSITORY = "https://github.com/invincible04/SplitFree";
const MAX_RESPONSE_BYTES = 256 * 1024;
const MAX_APK_BYTES = 512 * 1024 * 1024;
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";

function isRecord(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function parseRelease(data) {
    if (!isRecord(data) || data.draft !== false || data.prerelease !== false)
        throw new Error("Expected a published stable release");
    const tag = data.tag_name;
    const match =
        typeof tag === "string" && /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(tag);
    if (!match || match[0] !== tag || tag.length > 128)
        throw new Error("Invalid stable release tag");
    const pageUrl = `${REPOSITORY}/releases/tag/${tag}`;
    if (data.html_url !== pageUrl) throw new Error("Invalid official release URL");
    if (!Array.isArray(data.assets) || !data.assets.length)
        throw new Error("Missing release assets");

    const names = new Set();
    const apks = [];
    for (const asset of data.assets) {
        if (
            !isRecord(asset) ||
            typeof asset.name !== "string" ||
            asset.name.length > 255 ||
            asset.name.trim() !== asset.name ||
            !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(asset.name) ||
            asset.browser_download_url !== `${REPOSITORY}/releases/download/${tag}/${asset.name}`
        )
            throw new Error("Invalid release asset");
        const name = asset.name.toLowerCase();
        if (names.has(name)) throw new Error("Duplicate release asset");
        names.add(name);
        if (name.endsWith(".apk") || asset.content_type === APK_CONTENT_TYPE) apks.push(asset);
    }
    if (apks.length !== 1) throw new Error("Expected exactly one APK asset");
    const apk = apks[0];
    if (
        apk.name !== `SplitFree-${tag}.apk` ||
        apk.state !== "uploaded" ||
        !Number.isSafeInteger(apk.size) ||
        apk.size <= 0 ||
        apk.size > MAX_APK_BYTES ||
        typeof apk.digest !== "string" ||
        apk.digest.length !== 71 ||
        !/^sha256:[a-fA-F0-9]{64}$/.test(apk.digest)
    )
        throw new Error("Invalid uploaded APK metadata or missing SHA-256 digest");
    return {
        version: tag.slice(1),
        tag,
        apkUrl: apk.browser_download_url,
        pageUrl,
        sizeBytes: apk.size,
        // GitHub declares this digest; no APK bytes are downloaded or independently verified here.
        sha256: apk.digest.slice(7).toLowerCase(),
        verifiedAt: new Date().toISOString().slice(0, 10),
    };
}

function validateResponse(response) {
    if (!response || response.ok !== true || response.status !== 200)
        throw new Error(`Release request failed (HTTP ${response?.status ?? "unknown"})`);
    if (response.redirected || (response.url && response.url !== RELEASE_API))
        throw new Error("Unexpected release response URL");
    const contentType = response.headers?.get("content-type") ?? "";
    if (!/^application\/(?:json|vnd\.github\+json)(?:\s*;|$)/i.test(contentType))
        throw new Error("Expected a JSON release response");
    const length = response.headers.get("content-length");
    if (length !== null && (!/^\d+$/.test(length) || Number(length) > MAX_RESPONSE_BYTES))
        throw new Error("Release response exceeds the size limit or has an invalid length");
    if (typeof response.body?.getReader !== "function")
        throw new Error("Missing readable release response");
}

async function readReleaseJson(response, signal) {
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
            if (!(value instanceof Uint8Array)) throw new Error("Invalid release response bytes");
            size += value.byteLength;
            if (size > MAX_RESPONSE_BYTES)
                throw new Error("Release response exceeds the size limit");
            text += decoder.decode(value, { stream: true });
        }
        return JSON.parse(text + decoder.decode());
    } finally {
        signal.removeEventListener("abort", cancel);
        cancel();
        reader.releaseLock();
    }
}

async function requestRelease(fetchImpl, signal) {
    const response = await fetchImpl(RELEASE_API, {
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
        return parseRelease(await readReleaseJson(response, signal));
    } finally {
        // Also release unread bodies rejected by headers or returned after a mocked fetch times out.
        if (response?.body && !response.body.locked)
            void response.body.cancel(signal.reason).catch(() => {});
    }
}

export async function fetchLatestRelease(fetchImpl = globalThis.fetch, timeoutMs = 5000) {
    if (typeof fetchImpl !== "function") throw new TypeError("Fetch is unavailable");
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 2147483647)
        throw new RangeError("Invalid release request timeout");
    const controller = new AbortController();
    const timeoutError = new DOMException("Release request timed out", "TimeoutError");
    let timer;
    const deadline = new Promise((_, reject) => {
        timer = setTimeout(() => {
            reject(timeoutError);
            controller.abort(timeoutError);
        }, timeoutMs);
    });
    try {
        return await Promise.race([requestRelease(fetchImpl, controller.signal), deadline]);
    } catch (error) {
        controller.abort(error);
        throw error;
    } finally {
        clearTimeout(timer);
    }
}
