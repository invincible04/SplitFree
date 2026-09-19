import { readFile, mkdir, rm, cp, writeFile, readdir, lstat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { SECTION_INPUTS, assembleTemplate } from "./templates.mjs";

export const root = fileURLToPath(new URL("../", import.meta.url));
const escapeHtml = (value) =>
    String(value).replace(
        /[&<>"']/g,
        (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char],
    );

export function validateConfig(config) {
    const release = config?.release;
    if (
        config?.repository !== "https://github.com/invincible04/SplitFree" ||
        !release ||
        !/^\d+\.\d+\.\d+$/.test(release.version) ||
        release.tag !== `v${release.version}` ||
        !/^[a-f0-9]{64}$/.test(release.sha256) ||
        !Number.isSafeInteger(release.sizeBytes) ||
        release.sizeBytes <= 0
    )
        throw new Error("Invalid release configuration");
    if (
        release.apkUrl !==
            `${config.repository}/releases/download/${release.tag}/SplitFree-${release.tag}.apk` ||
        release.pageUrl !== `${config.repository}/releases/tag/${release.tag}`
    )
        throw new Error("Release URLs must point to the configured official APK and release");
    return config;
}

export function renderHtml(template, config) {
    validateConfig(config);
    const values = {
        repository: config.repository,
        apkUrl: config.release.apkUrl,
        version: config.release.version,
        size: (config.release.sizeBytes / 1e6).toFixed(1),
        releasePage: config.release.pageUrl,
        sha256: config.release.sha256,
    };
    const html = template.replace(/\{\{(\w+)\}\}/g, (_, key) => {
        if (!Object.hasOwn(values, key)) throw new Error(`Unknown template key: ${key}`);
        return escapeHtml(values[key]);
    });
    if (/\{\{|\}\}/.test(html)) throw new Error("Unresolved template expression");
    return html;
}

export const PUBLIC_INPUTS = new Set([
    "assets/splitfree-logo.svg",
    "assets/film-poster.webp",
    "assets/inter-variable.woff2",
    "assets/manrope-800.woff2",
    "assets/intro.mp4",
    "assets/film-description.vtt",
    "licenses/Inter-OFL.txt",
    "licenses/Manrope-OFL.txt",
    "licenses/Apache-2.0.txt",
]);

export async function validateTree(directory, allowed, exactPaths, base = directory) {
    const directoryStat = await lstat(directory);
    if (!directoryStat.isDirectory() || directoryStat.isSymbolicLink())
        throw new Error("Invalid build input directory");
    for (const entry of await readdir(directory)) {
        if (entry.startsWith(".")) throw new Error(`Hidden build input: ${entry}`);
        const full = path.join(directory, entry);
        const stat = await lstat(full);
        if (stat.isSymbolicLink()) throw new Error(`Symbolic build input: ${entry}`);
        if (stat.isDirectory()) await validateTree(full, allowed, exactPaths, base);
        else if (!stat.isFile() || !allowed.has(path.extname(entry)))
            throw new Error(`Unexpected build input: ${entry}`);
        else if (exactPaths && !exactPaths.has(path.relative(base, full).split(path.sep).join("/")))
            throw new Error(`Unreviewed build input: ${entry}`);
    }
}

export async function build() {
    const config = JSON.parse(await readFile(path.join(root, "site.config.json"), "utf8"));

    await validateTree(
        path.join(root, "src"),
        new Set([".css", ".js"]),
        new Set(["main.js", "split.js", "release.js", "stars.js", "styles.css", "footer.css"]),
    );
    await validateTree(
        path.join(root, "public"),
        new Set([".svg", ".woff2", ".webp", ".mp4", ".txt", ".vtt"]),
        PUBLIC_INPUTS,
    );
    await validateTree(path.join(root, "sections"), new Set([".html"]), SECTION_INPUTS);
    const sections = Object.fromEntries(
        await Promise.all(
            [...SECTION_INPUTS].map(async (name) => [
                name,
                await readFile(path.join(root, "sections", name), "utf8"),
            ]),
        ),
    );
    const html = renderHtml(
        assembleTemplate(await readFile(path.join(root, "index.html"), "utf8"), sections),
        config,
    );
    const output = path.join(root, "dist");
    await rm(output, { recursive: true, force: true });
    await mkdir(output, { recursive: true });
    await cp(path.join(root, "public"), output, { recursive: true });
    await cp(path.join(root, "src"), path.join(output, "src"), { recursive: true });
    await writeFile(path.join(output, "index.html"), html);
    await writeFile(path.join(output, ".nojekyll"), "");
    await cp(path.join(root, "../LICENSE"), path.join(output, "LICENSE.txt"));
    await cp(
        path.join(root, "THIRD_PARTY_NOTICES.md"),
        path.join(output, "THIRD-PARTY-NOTICES.txt"),
    );
    // No source tree, dependencies, APKs, signing files or test artifacts enter dist.
    console.log(
        `Built ${path.relative(process.cwd(), output)} for SplitFree v${config.release.version}`,
    );
    return output;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url))
    await build();
