/** Use integer minor units so every illustrative split adds back to the bill. */
export const CURRENCIES = Object.freeze([
    "USD",
    "EUR",
    "GBP",
    "JPY",
    "CAD",
    "AUD",
    "CHF",
    "CNY",
    "INR",
    "BRL",
    "KRW",
    "KWD",
]);
export function currencyDigits(currency = "INR") {
    if (!CURRENCIES.includes(currency)) throw new RangeError("Unsupported example currency");
    return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions()
        .maximumFractionDigits;
}
export function parseAmount(value, currency = "INR") {
    const digits = currencyDigits(currency);
    const pattern = digits ? new RegExp(`^\\d{1,6}(?:[.,]\\d{1,${digits}})?$`) : /^\d{1,6}$/;
    if (typeof value !== "string" || !pattern.test(value.trim())) return null;
    const [whole, fraction = ""] = value.trim().replace(",", ".").split(".");
    const scale = 10 ** digits;
    const minor = Number(whole) * scale + Number(fraction.padEnd(digits, "0"));
    return minor >= scale && minor <= 1000000 * scale - 1 ? minor : null;
}

export function allocate(total, weights) {
    if (
        !Number.isSafeInteger(total) ||
        total < 0 ||
        total > 999999999 ||
        !Array.isArray(weights) ||
        !weights.length ||
        weights.length > 100 ||
        weights.some((w) => !Number.isSafeInteger(w) || w <= 0 || w > 10000)
    )
        throw new RangeError("Invalid split");
    const sum = weights.reduce((a, b) => a + b, 0);
    const amounts = weights.map((weight) => Math.floor((total * weight) / sum));
    const order = weights
        .map((weight, index) => ({ index, remainder: (total * weight) % sum }))
        .sort((a, b) => b.remainder - a.remainder || a.index - b.index);
    const remaining = total - amounts.reduce((a, b) => a + b, 0);
    for (let i = 0; i < remaining; i++) amounts[order[i].index] += 1;
    return amounts;
}

export const examples = Object.freeze({
    equal: {
        weights: [1, 1, 1, 1],
        labels: ["¼ each", "¼ each", "¼ each", "¼ each"],
        note: "Try changing the bill or the split. Nothing is saved or sent.",
    },
    exact: {
        weights: [5, 5, 3, 3],
        labels: ["Custom", "Custom", "Custom", "Custom"],
        note: "A sample custom-amount split. In the app, you enter each person’s exact amount.",
    },
    percent: {
        weights: [40, 20, 20, 20],
        labels: ["40%", "20%", "20%", "20%"],
        note: "A sample 40 / 20 / 20 / 20 split. In the app, you choose the percentages.",
    },
    shares: {
        weights: [2, 1, 1, 1],
        labels: ["2 shares", "1 share", "1 share", "1 share"],
        note: "A sample 2 / 1 / 1 / 1 split. In the app, you choose everyone’s shares.",
    },
});

export function splitBill(total, mode) {
    if (!Object.hasOwn(examples, mode)) throw new RangeError("Unknown split mode");
    return allocate(total, examples[mode].weights);
}

export function formatMoney(minor, currency = "INR", locale = "en-US") {
    const digits = currencyDigits(currency);
    return new Intl.NumberFormat(locale, {
        style: "currency",
        currency,
        currencyDisplay: currency === "INR" ? "symbol" : "code",
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
    }).format(minor / 10 ** digits);
}
