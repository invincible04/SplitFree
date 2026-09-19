import { readFile, writeFile, rename, rm } from "node:fs/promises";
import { fetchLatestRelease } from "../src/release.js";
import { validateConfig } from "./build.mjs";

// Read-only GitHub API request; the only write is the local fallback configuration.
const file = new URL("../site.config.json", import.meta.url);
const temporary = new URL(`../.site-config-${crypto.randomUUID()}.tmp`, import.meta.url);
const config = JSON.parse(await readFile(file, "utf8"));
const release = await fetchLatestRelease();
validateConfig({ ...config, release });
let created = false;
try {
    await writeFile(temporary, JSON.stringify({ ...config, release }, null, 4) + "\n", {
        flag: "wx",
    });
    created = true;
    await rename(temporary, file);
} finally {
    if (created) await rm(temporary, { force: true });
}
console.log(
    `Local fallback synchronized with published GitHub release ${release.tag}. Nothing published.`,
);
