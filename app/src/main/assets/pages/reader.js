// Reader mode: the article's text and pictures on a clean page. Built from plain data (no HTML
// from the site), so nothing of the original page can run here.
"use strict";

const READER_FONTS = [
    ["Georgia, 'Noto Serif', serif", "Tırnaklı"],
    ["system-ui, sans-serif", "Sade"],
    ["'Righteous', system-ui, sans-serif", "Righteous"],
    ["'Dancing Script', cursive", "El yazısı"],
];
let size = 18;
let fontIndex = 0;
try {
    size = Number(localStorage.getItem("reader-size")) || 18;
    fontIndex = Number(localStorage.getItem("reader-font")) || 0;
} catch { /* storage off */ }

function applyReaderLook() {
    const article = $("article").style;
    article.setProperty("--reader-size", size + "px");
    article.setProperty("--reader-font", READER_FONTS[fontIndex % READER_FONTS.length][0]);
    try {
        localStorage.setItem("reader-size", String(size));
        localStorage.setItem("reader-font", String(fontIndex));
    } catch { /* storage off */ }
}

function render(article) {
    document.title = article.title || "Okuma modu";
    $("site").textContent = article.site || "";
    $("title").textContent = article.title || "";
    const nodes = [];
    for (const block of article.blocks || []) {
        let el;
        if (block.t === "img") {
            el = document.createElement("img");
            el.alt = "";
            el.loading = "lazy";
            el.src = block.src;
        } else {
            el = document.createElement(block.t === "h" ? "h2" : block.t === "quote" ? "blockquote" : "p");
            el.textContent = block.text;
        }
        nodes.push(el);
    }
    $("body").replaceChildren(...nodes);
}

onAppMessage((message) => {
    if (message.type === "reader:article") render(message);
});

$("back").addEventListener("click", () => send({ type: "reader:close" }));
$("smaller").addEventListener("click", () => { size = Math.max(13, size - 1); applyReaderLook(); });
$("bigger").addEventListener("click", () => { size = Math.min(30, size + 1); applyReaderLook(); });
$("font").addEventListener("click", () => {
    fontIndex = (fontIndex + 1) % READER_FONTS.length;
    applyReaderLook();
    $("font").title = READER_FONTS[fontIndex][1];
});

applyReaderLook();
send({ type: "ready" });
send({ type: "reader:get" });
