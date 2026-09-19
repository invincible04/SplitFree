import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
    testDir: "./tests",
    testMatch: "**/browser.test.mjs",
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    workers: process.env.CI ? 2 : 4,
    reporter: [["list"], ["html", { open: "never" }]],
    use: {
        baseURL: "http://127.0.0.1:4173",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
    },
    projects: [
        {
            name: "desktop-chromium",
            use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } },
        },
        {
            name: "mobile-chromium",
            use: { ...devices["Pixel 7"], viewport: { width: 393, height: 851 } },
        },
        {
            name: "mobile-webkit",
            use: { ...devices["iPhone 13"], viewport: { width: 390, height: 844 } },
        },
        {
            name: "desktop-webkit",
            use: { ...devices["Desktop Safari"], viewport: { width: 1280, height: 900 } },
        },
    ],
    webServer: {
        command: "node scripts/serve.mjs",
        url: "http://127.0.0.1:4173",
        reuseExistingServer: !process.env.CI,
    },
});
