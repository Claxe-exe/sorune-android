// New tab: greeting, clock, search and shortcuts over the theme's wallpaper.
"use strict";

const SHORTCUTS = [
    { name: "YouTube", url: "https://m.youtube.com" },
    { name: "Google", url: "https://www.google.com" },
    { name: "Webtoon", url: "https://www.webtoons.com" },
    { name: "Wikipedia", url: "https://tr.m.wikipedia.org" },
    { name: "GitHub", url: "https://github.com" },
    { name: "Twitch", url: "https://m.twitch.tv" },
    { name: "Reddit", url: "https://www.reddit.com" },
    { name: "Ayarlar", url: "https://appassets.androidplatform.net/pages/settings.html", icon: "⚙️" },
];

function tick() {
    const now = new Date();
    const hour = now.getHours();
    $("greeting").textContent = hour < 5 ? "İyi geceler" : hour < 12 ? "Günaydın" : hour < 18 ? "İyi günler" : hour < 22 ? "İyi akşamlar" : "İyi geceler";
    $("clock").textContent = now.toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" });
    $("date").textContent = now.toLocaleDateString("tr-TR", { day: "numeric", month: "long", weekday: "long" });
}

// Bookmarks come first, the defaults fill up the row of eight.
let bookmarks = [];
onAppMessage((message) => {
    if (message.type === "config") {
        bookmarks = message.bookmarks || [];
        renderShortcuts();
    }
});

function renderShortcuts() {
    const nav = $("shortcuts");
    const seen = new Set();
    const list = [...bookmarks, ...SHORTCUTS].filter((s) => {
        let host;
        try { host = new URL(s.url).href; } catch { return false; }
        if (seen.has(host)) return false;
        seen.add(host);
        return true;
    }).slice(0, 8);
    nav.replaceChildren(...list.map((s) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "shortcut";
        const icon = document.createElement("div");
        icon.className = "icon";
        if (s.icon) {
            icon.textContent = s.icon;
            icon.style.fontSize = "24px";
        } else {
            const img = document.createElement("img");
            img.alt = "";
            img.src = `https://www.google.com/s2/favicons?sz=64&domain=${new URL(s.url).hostname}`;
            icon.append(img);
        }
        const label = document.createElement("span");
        label.textContent = s.name;
        button.append(icon, label);
        button.addEventListener("click", () => { location.href = s.url; });
        return button;
    }));
}

$("search").addEventListener("submit", (event) => {
    event.preventDefault();
    const text = $("query").value.trim();
    if (text) send({ type: "navigate", text });
});

tick();
setInterval(tick, 10000);
renderShortcuts();
send({ type: "ready" });
