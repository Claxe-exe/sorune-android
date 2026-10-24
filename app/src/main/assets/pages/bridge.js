// Talks to the app: messages go out as JSON on the channel the app opens for its own pages
// (window.soruneHost); the theme comes back as CSS variables.
"use strict";

const host = window.soruneHost;
const $ = (id) => document.getElementById(id);

function send(message) {
    if (host) host.postMessage(JSON.stringify(message));
}

const handlers = [];
function onAppMessage(handler) {
    handlers.push(handler);
}

if (host) {
    host.onmessage = (event) => {
        let message;
        try { message = JSON.parse(event.data); } catch { return; }
        if (message.type === "config") applyTheme(message.theme || {});
        handlers.forEach((h) => h(message));
    };
}

function hexToRgb(hex) {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})/i.exec(hex || "");
    return m ? `${parseInt(m[1], 16)}, ${parseInt(m[2], 16)}, ${parseInt(m[3], 16)}` : null;
}

function applyTheme(theme) {
    const root = document.documentElement.style;
    const map = { base: "--base", surface: "--surface", border: "--border", textPrimary: "--text", textSecondary: "--text-dim", accent: "--accent", accentSecondary: "--accent2" };
    for (const [key, variable] of Object.entries(map)) if (theme[key]) root.setProperty(variable, theme[key]);
    const overlay = hexToRgb(theme.overlay);
    if (overlay) root.setProperty("--overlay", overlay);
    const wall = $("wall");
    if (wall && theme.wallpaper) {
        root.setProperty("--wallpaper", `url("${theme.wallpaper}")`);
        wall.classList.add("ready");
        focusWallpaper(theme.wallpaper, typeof theme.focusX === "number" ? theme.focusX : 0.5);
    }
}

// A phone held upright sees a narrow slice of a wide wallpaper: slide it so the character
// (theme.focusX across the picture) sits in the middle of the screen.
let focus = null;
function focusWallpaper(url, focusX) {
    const image = new Image();
    image.onload = () => {
        focus = { ratio: image.naturalWidth / image.naturalHeight, x: focusX };
        placeWallpaper();
    };
    image.src = url;
}

// The keyboard makes the page shorter. The wallpaper and the page keep the full height (--full-h)
// so nothing jumps or rescales while typing; a new width (turned phone) starts over.
let fullWidth = innerWidth;
let fullHeight = innerHeight;
function keepFullHeight() {
    if (innerWidth !== fullWidth) {
        fullWidth = innerWidth;
        fullHeight = innerHeight;
    } else {
        fullHeight = Math.max(fullHeight, innerHeight);
    }
    document.documentElement.style.setProperty("--full-h", fullHeight + "px");
}
keepFullHeight();
addEventListener("resize", keepFullHeight);

function placeWallpaper() {
    if (!focus) return;
    const visible = (innerWidth / fullHeight) / focus.ratio; // share of the picture's width on screen
    const position = visible >= 1 ? 0.5 : Math.min(1, Math.max(0, (focus.x - visible / 2) / (1 - visible)));
    document.documentElement.style.setProperty("--wall-x", (position * 100).toFixed(1) + "%");
}
addEventListener("resize", placeWallpaper);
