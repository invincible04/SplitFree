import { createServer } from "node:http";
import { createReadStream } from "node:fs";
import { stat, realpath } from "node:fs/promises";
import path from "node:path";
import { build } from "./build.mjs";

const root = await realpath(await build());
const port = Number(process.env.PORT || 4173);
const host = "127.0.0.1";
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("Invalid preview port");
const types = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml",
    ".ttf": "font/ttf",
    ".woff2": "font/woff2",
    ".mp4": "video/mp4",
    ".webp": "image/webp",
    ".png": "image/png",
    ".vtt": "text/vtt; charset=utf-8",
    ".txt": "text/plain; charset=utf-8",
};
const server = createServer(async (req, res) => {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Cache-Control", "no-cache");
    if (!["GET", "HEAD"].includes(req.method)) {
        res.writeHead(405, { Allow: "GET, HEAD" }).end();
        return;
    }
    try {
        const urlPath = decodeURIComponent(new URL(req.url, `http://${host}`).pathname);
        // Also serve a real project subpath for GitHub Pages regression testing.
        const relative = urlPath === "/SplitFree" ? "/" : urlPath.replace(/^\/SplitFree\//, "/");
        const candidate = path.resolve(
            root,
            `.${relative.endsWith("/") ? `${relative}index.html` : relative}`,
        );
        if (!candidate.startsWith(`${root}${path.sep}`)) {
            res.writeHead(403).end();
            return;
        }
        const file = await realpath(candidate);
        if (!file.startsWith(`${root}${path.sep}`)) {
            res.writeHead(403).end();
            return;
        }
        const info = await stat(file);
        if (!info.isFile()) {
            res.writeHead(404).end("Not found");
            return;
        }
        let start = 0;
        let end = info.size - 1;
        let status = 200;
        res.setHeader("Content-Type", types[path.extname(file)] || "application/octet-stream");
        res.setHeader("Accept-Ranges", "bytes");
        if (req.headers.range) {
            const match = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
            if (!match || (!match[1] && !match[2])) {
                res.writeHead(416, { "Content-Range": `bytes */${info.size}` }).end();
                return;
            }
            if (!match[1]) start = Math.max(0, info.size - Number(match[2]));
            else {
                start = Number(match[1]);
                if (match[2]) end = Math.min(end, Number(match[2]));
            }
            if (
                !Number.isSafeInteger(start) ||
                !Number.isSafeInteger(end) ||
                start > end ||
                start >= info.size
            ) {
                res.writeHead(416, { "Content-Range": `bytes */${info.size}` }).end();
                return;
            }
            status = 206;
            res.setHeader("Content-Range", `bytes ${start}-${end}/${info.size}`);
        }
        res.writeHead(status, { "Content-Length": Math.max(0, end - start + 1) });
        if (req.method === "HEAD" || !info.size) {
            res.end();
            return;
        }
        const stream = createReadStream(file, { start, end });
        stream.on("error", () => res.destroy());
        res.on("close", () => stream.destroy());
        stream.pipe(res);
    } catch {
        res.writeHead(404).end("Not found");
    }
});
server.listen(port, host, () => console.log(`SplitFree preview: http://${host}:${port}/`));
