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
    }
}
