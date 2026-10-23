// New tab: greeting, clock, search and shortcuts over the theme's wallpaper. Fonts, clock style,
// effects, dimming and the shortcuts themselves come from the user's new tab settings.
"use strict";

const DEFAULT_SHORTCUTS = [
    { name: "YouTube", url: "https://m.youtube.com" },
    { name: "Google", url: "https://www.google.com" },
    { name: "Webtoon", url: "https://www.webtoons.com" },
    { name: "Wikipedia", url: "https://tr.m.wikipedia.org" },
    { name: "GitHub", url: "https://github.com" },
    { name: "Twitch", url: "https://m.twitch.tv" },
    { name: "Reddit", url: "https://www.reddit.com" },
    { name: "Ayarlar", url: "https://appassets.androidplatform.net/pages/settings.html", icon: "⚙️" },
];

const FONTS = {
    system: "system-ui, sans-serif",
    orbitron: "'Orbitron', system-ui, sans-serif",
    bebas: "'Bebas Neue', system-ui, sans-serif",
    pacifico: "'Pacifico', cursive",
    pixel: "'Press Start 2P', monospace",
    righteous: "'Righteous', system-ui, sans-serif",
    script: "'Dancing Script', cursive",
};

let options = {};      // new tab settings from the app
let bookmarks = [];
let themeEffect = "none";
let editing = false;

// ---------------------------------------------------------------- clock

function tick() {
    const now = new Date();
    const hour = now.getHours();
    $("greeting").textContent = hour < 5 ? "İyi geceler" : hour < 12 ? "Günaydın" : hour < 18 ? "İyi günler" : hour < 22 ? "İyi akşamlar" : "İyi geceler";
    $("greeting").hidden = options.greeting === false;
    $("clock").textContent = now.toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit", hour12: Boolean(options.hour12) });
    $("date").textContent = now.toLocaleDateString("tr-TR", { day: "numeric", month: "long", weekday: "long" });
}

// ---------------------------------------------------------------- look

function applyOptions() {
    const root = document.documentElement.style;
    root.setProperty("--clock-font", FONTS[options.clockFont] || FONTS.system);
    root.setProperty("--label-font", FONTS[options.labelFont] || FONTS.system);
    root.setProperty("--wall-blur", `${Math.min(20, Math.max(0, Number(options.blur) || 0))}px`);
    root.setProperty("--wall-dim", String(Math.min(0.8, Math.max(0, (Number(options.dim) || 0) / 100))));
    // The pixel font is wide: a smaller clock keeps it on one line.
    $("clock").classList.toggle("small", options.clockFont === "pixel");
    tick();
    startEffect(options.effect && options.effect !== "theme" ? options.effect : themeEffect);
}

// ---------------------------------------------------------------- shortcuts

function shortcuts() {
    return Array.isArray(options.shortcuts) ? options.shortcuts : DEFAULT_SHORTCUTS;
}

function saveShortcuts(list) {
    options.shortcuts = list;
    send({ type: "newtab:shortcuts", items: list });
    renderShortcuts();
}

function renderShortcuts() {
    const own = shortcuts();
    const shown = editing ? own : [...(options.bookmarks === false ? [] : bookmarks), ...own];
    const seen = new Set();
    const list = shown.filter((s) => {
        let key;
        try { key = new URL(s.url).href; } catch { return false; }
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    }).slice(0, editing ? 16 : 12);

    const tiles = list.map((s) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "shortcut";
        const icon = document.createElement("div");
        icon.className = "icon";
        if (s.icon) {
            icon.textContent = s.icon;
        } else {
            const img = document.createElement("img");
            img.alt = "";
            img.src = `https://www.google.com/s2/favicons?sz=64&domain=${new URL(s.url).hostname}`;
            icon.append(img);
        }
        const label = document.createElement("span");
        label.textContent = s.name;
        button.append(icon, label);

        if (editing) {
            const index = own.indexOf(s);
            const tools = document.createElement("div");
            tools.className = "tools";
            const tool = (text, cls, title, action) => {
                const b = document.createElement("button");
                b.type = "button";
                b.className = cls;
                b.textContent = text;
                b.setAttribute("aria-label", title);
                b.addEventListener("click", (event) => { event.stopPropagation(); action(); });
                return b;
            };
            tools.append(
                tool("◀", "move", "Sola taşı", () => {
                    if (index <= 0) return;
                    const copy = [...own];
                    [copy[index - 1], copy[index]] = [copy[index], copy[index - 1]];
                    saveShortcuts(copy);
                }),
                tool("✕", "remove", "Kaldır", () => saveShortcuts(own.filter((_, i) => i !== index))),
                tool("▶", "move", "Sağa taşı", () => {
                    if (index >= own.length - 1) return;
                    const copy = [...own];
                    [copy[index + 1], copy[index]] = [copy[index], copy[index + 1]];
                    saveShortcuts(copy);
                }),
            );
            button.append(tools);
        } else {
            button.addEventListener("click", () => { location.href = s.url; });
        }
        return button;
    });

    if (editing && own.length < 16) {
        const add = document.createElement("button");
        add.type = "button";
        add.className = "shortcut";
        const icon = document.createElement("div");
        icon.className = "icon";
        icon.textContent = "+";
        const label = document.createElement("span");
        label.textContent = "Ekle";
        add.append(icon, label);
        add.addEventListener("click", () => {
            $("add-form").hidden = false;
            $("add-name").focus();
        });
        tiles.push(add);
    }
    $("shortcuts").replaceChildren(...tiles);
}

$("edit").addEventListener("click", () => {
    editing = !editing;
    document.body.classList.toggle("editing", editing);
    $("edit").textContent = editing ? "✓ Bitti" : "✎ Düzenle";
    $("add-form").hidden = true;
    renderShortcuts();
});

$("add-cancel").addEventListener("click", () => { $("add-form").hidden = true; });

$("add-form").addEventListener("submit", (event) => {
    event.preventDefault();
    let url = $("add-url").value.trim();
    if (!url) return;
    if (!/^https?:\/\//i.test(url)) url = "https://" + url;
    let host;
    try { host = new URL(url).hostname; } catch { return; }
    const name = $("add-name").value.trim() || host.replace(/^www\./, "");
    saveShortcuts([...shortcuts(), { name: name.slice(0, 24), url }]);
    $("add-name").value = "";
    $("add-url").value = "";
    $("add-form").hidden = true;
});

// ---------------------------------------------------------------- effects

const canvas = $("effects");
const ctx = canvas.getContext("2d");
let particles = [];
let effect = "none";
let frame = 0;

function startEffect(name) {
    effect = ["petals", "snow", "sparks", "bubbles"].includes(name) ? name : "none";
    cancelAnimationFrame(frame);
    resize();
    particles = effect === "none" ? [] : Array.from({ length: effect === "snow" ? 60 : 32 }, () => spawn(true));
    if (effect === "none") ctx.clearRect(0, 0, canvas.width, canvas.height);
    else frame = requestAnimationFrame(draw);
}

function resize() {
    canvas.width = innerWidth * devicePixelRatio;
    canvas.height = innerHeight * devicePixelRatio;
}

function spawn(anywhere) {
    const w = canvas.width, h = canvas.height, d = devicePixelRatio;
    const up = effect === "sparks" || effect === "bubbles";
    return {
        x: Math.random() * w,
        y: anywhere ? Math.random() * h : (up ? h + 20 * d : -20 * d),
        size: (effect === "snow" ? 1.5 + Math.random() * 3 : effect === "bubbles" ? 4 + Math.random() * 10 : 3 + Math.random() * 5) * d,
        vx: (Math.random() - 0.5) * 0.6 * d,
        vy: (up ? -1 : 1) * (0.4 + Math.random() * 1.1) * d,
        spin: Math.random() * Math.PI * 2,
        life: Math.random(),
    };
}

function draw() {
    const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#b583ff";
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (let i = 0; i < particles.length; i++) {
        const p = particles[i];
        p.x += p.vx + Math.sin(p.spin) * 0.3;
        p.y += p.vy;
        p.spin += 0.02;
        p.life += 0.01;
        if (p.y > canvas.height + 30 || p.y < -30 || p.x < -30 || p.x > canvas.width + 30) particles[i] = spawn(false);
        ctx.save();
        ctx.translate(p.x, p.y);
        if (effect === "petals") {
            ctx.rotate(p.spin);
            ctx.fillStyle = accent;
            ctx.globalAlpha = 0.55;
            ctx.beginPath();
            ctx.ellipse(0, 0, p.size, p.size * 0.55, 0, 0, Math.PI * 2);
            ctx.fill();
        } else if (effect === "snow") {
            ctx.fillStyle = "#ffffff";
            ctx.globalAlpha = 0.75;
            ctx.beginPath();
            ctx.arc(0, 0, p.size, 0, Math.PI * 2);
            ctx.fill();
        } else if (effect === "sparks") {
            ctx.fillStyle = accent;
            ctx.shadowColor = accent;
            ctx.shadowBlur = 8 * devicePixelRatio;
            ctx.globalAlpha = 0.4 + 0.5 * Math.abs(Math.sin(p.life * 6));
            ctx.beginPath();
            ctx.arc(0, 0, p.size * 0.45, 0, Math.PI * 2);
            ctx.fill();
        } else {
            ctx.strokeStyle = accent;
            ctx.globalAlpha = 0.45;
            ctx.lineWidth = 1.2 * devicePixelRatio;
            ctx.beginPath();
            ctx.arc(0, 0, p.size, 0, Math.PI * 2);
            ctx.stroke();
        }
        ctx.restore();
    }
    frame = requestAnimationFrame(draw);
}

addEventListener("resize", resize);
// No animation while the tab is in the background.
document.addEventListener("visibilitychange", () => {
    if (document.hidden) cancelAnimationFrame(frame);
    else if (effect !== "none") frame = requestAnimationFrame(draw);
});

// ---------------------------------------------------------------- start

$("search").addEventListener("submit", (event) => {
    event.preventDefault();
    const text = $("query").value.trim();
    if (text) send({ type: "navigate", text });
});

onAppMessage((message) => {
    if (message.type !== "config") return;
    options = message.newtab || {};
    bookmarks = message.bookmarks || [];
    themeEffect = (message.theme && message.theme.particles) || "none";
    applyOptions();
    renderShortcuts();
});

tick();
setInterval(tick, 10000);
renderShortcuts();
send({ type: "ready" });
