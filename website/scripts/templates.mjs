/** Build-time sections only: no browser fetches, template evaluation or arbitrary paths. */
export const SECTION_INPUTS = new Set([
    "head.html",
    "icons.html",
    "header.html",
    "hero.html",
    "principles.html",
    "how-it-works.html",
    "split-demo.html",
    "features.html",
    "freedom.html",
    "faq.html",
    "download.html",
    "footer.html",
    "film.html",
]);

export function assembleTemplate(template, sections) {
    const used = new Set();
    for (const [name, content] of Object.entries(sections)) {
        if (!SECTION_INPUTS.has(name) || typeof content !== "string")
            throw new Error(`Unreviewed section: ${name}`);
    }
    function expand(content, stack = []) {
        const expanded = content.replace(/<!--\s*include:([\s\S]*?)-->/g, (_, raw) => {
            const name = raw.trim();
            if (!SECTION_INPUTS.has(name) || !Object.hasOwn(sections, name))
                throw new Error(`Unknown section include: ${name}`);
            if (stack.includes(name)) throw new Error(`Circular section include: ${name}`);
            if (used.has(name)) throw new Error(`Duplicate section include: ${name}`);
            used.add(name);
            return expand(sections[name], [...stack, name]);
        });
        if (/<!--\s*include\b/.test(expanded)) throw new Error("Malformed section include");
        return expanded;
    }
    const result = expand(template);
    for (const name of Object.keys(sections))
        if (!used.has(name)) throw new Error(`Unused section: ${name}`);
    return result;
}
