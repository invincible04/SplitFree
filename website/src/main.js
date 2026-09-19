import { parseAmount, splitBill, formatMoney, examples, currencyDigits } from "./split.js";

// Add enhancements only after the module and its local dependencies have loaded.
document.documentElement.classList.add("js");
const menu = document.querySelector(".menu-toggle");
const nav = document.querySelector("#site-nav");
menu.hidden = false;
function closeMenu(restoreFocus = false) {
    const focusedLink = nav.contains(document.activeElement);
    nav.classList.remove("is-open");
    menu.setAttribute("aria-expanded", "false");
    menu.setAttribute("aria-label", "Open menu");
    const focusedLinkHidden = focusedLink && !nav.getClientRects().length;
    if ((restoreFocus || focusedLinkHidden) && menu.getClientRects().length)
        menu.focus({ preventScroll: true });
}
menu.addEventListener("click", () => {
    const open = menu.getAttribute("aria-expanded") !== "true";
    nav.classList.toggle("is-open", open);
    menu.setAttribute("aria-expanded", String(open));
    menu.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    if (open) nav.querySelector("a").focus();
});
nav.addEventListener("click", (event) => {
    const link = event.target.closest("a");
    if (!link) return;
    const mobile = menu.getClientRects().length > 0;
    closeMenu();
    const href = link.getAttribute("href");
    if (
        mobile &&
        href?.startsWith("#") &&
        !event.metaKey &&
        !event.ctrlKey &&
        !event.shiftKey &&
        !event.altKey
    ) {
        const destination = document.getElementById(href.slice(1));
        if (destination) {
            // Continue keyboard navigation at the section, not the now-hidden menu.
            destination.tabIndex = -1;
            destination.focus({ preventScroll: true });
        }
    }
});
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && menu.getAttribute("aria-expanded") === "true") closeMenu(true);
});
document.addEventListener("click", (event) => {
    if (!event.target.closest(".site-header")) closeMenu();
});
matchMedia("(min-width: 700px)").addEventListener("change", () => closeMenu());

const demo = document.querySelector("#split-demo");
const amount = document.querySelector("#bill-amount");
const error = document.querySelector("#amount-error");
const summary = document.querySelector("#split-summary");
const currencySelect = document.querySelector("#demo-currency");
const locale = navigator.language || "en-US";
function renderSplit() {
    const currency = currencySelect.value;
    amount.parentElement.classList.toggle("long-amount", amount.value.trim().length > 7);
    const digits = currencyDigits(currency);
    const money = (value) => formatMoney(value, currency, locale);
    const currencyPart = new Intl.NumberFormat(locale, {
        style: "currency",
        currency,
        currencyDisplay: "narrowSymbol",
    })
        .formatToParts(0)
        .find((part) => part.type === "currency");
    document.querySelector("#currency-symbol").textContent = currencyPart.value;
    document.querySelector("#currency-name").textContent = currency;
    const guidance = `Enter 1 to ${digits ? `999999.${"9".repeat(digits)}` : "999999"} ${currency}. ${digits ? `Use up to ${digits} decimal places, with a dot or comma and no grouping separators.` : "Use whole amounts without decimals."}`;
    document.querySelector("#amount-help").textContent = guidance;
    const total = parseAmount(amount.value, currency);
    const mode = demo.querySelector('input[name="mode"]:checked').value;
    if (total === null) {
        amount.setAttribute("aria-invalid", "true");
        error.hidden = false;
        error.textContent = guidance;
        document.querySelectorAll("[data-person]").forEach((output) => {
            output.textContent = "—";
        });
        summary.textContent = "Enter a valid bill to see the split.";
        return;
    }
    error.hidden = true;
    error.textContent = "";
    amount.removeAttribute("aria-invalid");
    const values = splitBill(total, mode);
    document.querySelectorAll("[data-person]").forEach((output, index) => {
        output.textContent = money(values[index]);
    });
    document.querySelectorAll("[data-weight]").forEach((label, index) => {
        label.textContent = examples[mode].labels[index];
    });
    summary.textContent =
        mode === "equal" && values.every((value) => value === values[0])
            ? `${money(values[0])} each. Nicely split.`
            : `${money(total)} accounted for. The split adds up exactly.`;
    document.querySelector("#demo-note").textContent = examples[mode].note;
}
demo.addEventListener("input", renderSplit);
demo.addEventListener("change", renderSplit);
amount.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
        event.preventDefault();
        amount.blur();
    }
});
demo.disabled = false;
renderSplit();

const dialog = document.querySelector("#film-dialog");
const film = document.querySelector("#intro-film");
const filmError = document.querySelector("#film-error");
let filmOpener;
if (typeof dialog.showModal === "function") {
    const source = film.querySelector("source");
    const failed = () => film.error || film.networkState === film.NETWORK_NO_SOURCE;
    document.querySelectorAll(".film-trigger").forEach((link) =>
        link.addEventListener("click", (event) => {
            event.preventDefault();
            filmOpener = event.currentTarget;
            // A failed <source> keeps its URL, so retry explicitly without resetting healthy playback.
            if (!source.src || failed()) {
                source.src = source.dataset.src;
                film.load();
            }
            dialog.showModal();
            // Keep an existing fallback visible while a retry is still loading.
            film.play().catch(() => {
                // Autoplay denial or closing the dialog is not a media load failure.
                if (failed()) filmError.hidden = false;
            });
        }),
    );
    function closeFilm() {
        film.pause();
        dialog.close();
    }
    dialog.querySelector(".film-close").addEventListener("click", closeFilm);
    dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        closeFilm();
    });
    film.addEventListener("play", () => {
        if (!dialog.open) film.pause();
    });
    dialog.addEventListener("click", (event) => {
        const bounds = dialog.getBoundingClientRect();
        if (
            event.target === dialog &&
            (event.clientX < bounds.left ||
                event.clientX > bounds.right ||
                event.clientY < bounds.top ||
                event.clientY > bounds.bottom)
        )
            closeFilm();
    });
    dialog.addEventListener("close", () => {
        film.pause();
        filmOpener?.focus({ preventScroll: true });
    });
    film.addEventListener("error", () => {
        filmError.hidden = false;
    });
    source.addEventListener("error", () => {
        filmError.hidden = false;
    });
    film.addEventListener("canplay", () => {
        if (!failed() && film.readyState >= film.HAVE_FUTURE_DATA) filmError.hidden = true;
    });
}

const copyButton = document.querySelector(".copy-checksum");
if (navigator.clipboard?.writeText) {
    copyButton.hidden = false;
    copyButton.addEventListener("click", async () => {
        const status = document.querySelector("#copy-status");
        try {
            await navigator.clipboard.writeText(
                document.querySelector("#apk-checksum").textContent.trim(),
            );
            status.textContent = "Copied.";
        } catch {
            status.textContent = "Select and copy the checksum above.";
        }
    });
}

// Content is visible without JS; only newly encountered sections get a one-shot entrance.
if ("IntersectionObserver" in window && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
    const observer = new IntersectionObserver(
        (entries) => {
            entries.forEach((entry) => {
                if (!entry.isIntersecting) return;
                entry.target.classList.add("enter-view");
                observer.unobserve(entry.target);
            });
        },
        { threshold: 0.12 },
    );
    document
        .querySelectorAll(".section-heading, .feature-card, .freedom-layout, .download-copy")
        .forEach((node) => observer.observe(node));
}

// Independent enhancement: a failed release check must never disable the demo or navigation.
async function syncRelease() {
    const status = document.querySelector("#release-status");
    const bundledVersion = document.querySelector("[data-release-version]").textContent;
    let pinnedByVisitor = false;
    const pin = () => {
        pinnedByVisitor = true;
    };
    document
        .querySelectorAll("[data-download], [data-release-page], .checksum-details")
        .forEach((link) => {
            link.addEventListener("pointerdown", pin, { once: true });
            link.addEventListener("click", pin, { once: true });
            link.addEventListener("keydown", (event) => {
                if (event.key === "Enter" || event.key === " ") pin();
            });
        });
    status.textContent = "Checking the latest published release on GitHub…";
    try {
        const { fetchLatestRelease } = await import("./release.js");
        const release = await fetchLatestRelease();
        if (pinnedByVisitor || document.querySelector(".checksum-details").open) {
            status.textContent = `Release details kept at ${bundledVersion} while you use them. Latest on GitHub: v${release.version}.`;
            return;
        }
        // Commit one validated release synchronously, never mix an old checksum with a new APK.
        document.querySelectorAll("[data-download]").forEach((link) => {
            link.href = release.apkUrl;
        });
        document.querySelectorAll("[data-release-page]").forEach((link) => {
            link.href = release.pageUrl;
        });
        document.querySelectorAll("[data-release-version]").forEach((node) => {
            node.textContent = `v${release.version}`;
        });
        document.querySelectorAll("[data-release-size]").forEach((node) => {
            node.textContent = `${(release.sizeBytes / 1e6).toFixed(1)} MB`;
        });
        document.querySelectorAll("[data-release-filename]").forEach((node) => {
            node.textContent = `SplitFree-${release.tag}.apk`;
        });
        document.querySelector("#apk-checksum").textContent = release.sha256;
        document.querySelector("#copy-status").textContent = "";
        status.textContent = `Latest published release: v${release.version}. Checked with GitHub.`;
    } catch {
        status.textContent = `Live check unavailable. Showing bundled ${bundledVersion}; check GitHub for newer releases.`;
    }
}
syncRelease();

// Optional social metadata never blocks release checks or local interactions.
async function updateStars() {
    try {
        const { fetchStars, starLabel } = await import("./stars.js");
        const count = await fetchStars();
        document.querySelectorAll("[data-star-label]").forEach((node) => {
            node.textContent = starLabel(count);
        });
    } catch {
        // Keep the ordinary GitHub link, without an invented or stale count.
    }
}
updateStars();
