export const RELEASE_API = "https://api.github.com/repos/invincible04/SplitFree/releases/latest";
export function releaseFixture(release) {
    return {
        tag_name: release.tag,
        html_url: release.pageUrl,
        draft: false,
        prerelease: false,
        published_at: "2026-09-17T22:44:59Z",
        assets: [
            {
                name: `SplitFree-${release.tag}.apk`,
                state: "uploaded",
                size: release.sizeBytes,
                browser_download_url: release.apkUrl,
                digest: `sha256:${release.sha256}`,
            },
        ],
    };
}
