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
    showGlass(options.glass ?? 35);
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

// How dark the shortcut and widget cards are, 0 = only the wallpaper shows.
function showGlass(value) {
    const v = Math.min(90, Math.max(0, Number(value) || 0));
    document.documentElement.style.setProperty("--card", `${v}%`);
    $("glass").value = v;
    $("glass-value").textContent = `%${v}`;
}
$("glass").addEventListener("input", (e) => showGlass(e.target.value));
$("glass").addEventListener("change", (e) => send({ type: "newtab:set", key: "glass", value: Number(e.target.value) }));

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

// ---------------------------------------------------------------- widgets

const WEATHER = {
    0: ["☀️", "Açık"], 1: ["🌤", "Az bulutlu"], 2: ["⛅", "Parçalı bulutlu"], 3: ["☁️", "Bulutlu"],
    45: ["🌫", "Sisli"], 48: ["🌫", "Sisli"], 51: ["🌦", "Çiseliyor"], 53: ["🌦", "Çiseliyor"], 55: ["🌦", "Çiseliyor"],
    61: ["🌧", "Yağmurlu"], 63: ["🌧", "Yağmurlu"], 65: ["🌧", "Sağanak"], 71: ["🌨", "Karlı"], 73: ["🌨", "Karlı"],
    75: ["❄️", "Yoğun kar"], 80: ["🌦", "Sağanak"], 81: ["🌧", "Sağanak"], 82: ["⛈", "Şiddetli sağanak"], 95: ["⛈", "Fırtına"],
};

function widgetData() {
    return options.widgets || {};
}

function saveWidgets(data) {
    options.widgets = data;
    send({ type: "newtab:widgets", data });
}

function card(title) {
    const box = document.createElement("div");
    box.className = "widget";
    const h = document.createElement("h3");
    h.textContent = title;
    box.append(h);
    return box;
}

async function weatherFor(city) {
    // Cached for half an hour: the new tab opens often.
    try {
        const cached = JSON.parse(localStorage.getItem("weather") || "null");
        if (cached && cached.city === city && Date.now() - cached.time < 30 * 60 * 1000) return cached;
    } catch { /* storage off */ }
    const geo = await (await fetch(`https://geocoding-api.open-meteo.com/v1/search?count=1&language=tr&name=${encodeURIComponent(city)}`)).json();
    const place = geo.results && geo.results[0];
    if (!place) throw new Error("Şehir bulunamadı");
    const data = await (await fetch(`https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1`)).json();
    const result = {
        city, time: Date.now(), name: place.name,
        temp: Math.round(data.current.temperature_2m), code: data.current.weather_code,
        max: Math.round(data.daily.temperature_2m_max[0]), min: Math.round(data.daily.temperature_2m_min[0]),
    };
    try { localStorage.setItem("weather", JSON.stringify(result)); } catch { /* storage off */ }
    return result;
}

function weatherWidget() {
    const box = card("Hava durumu");
    const data = widgetData();
    const askCity = () => {
        const input = document.createElement("input");
        input.placeholder = "Şehir yaz, ör. İstanbul";
        input.enterKeyHint = "done";
        input.addEventListener("keydown", (e) => {
            if (e.key === "Enter" && input.value.trim()) saveWidgetsAndRender({ ...widgetData(), city: input.value.trim().slice(0, 60) });
        });
        box.append(input);
    };
    if (!data.city) {
        askCity();
        return box;
    }
    const now = document.createElement("div");
    now.className = "weather-now";
    now.textContent = "Yükleniyor…";
    box.append(now);
    weatherFor(data.city).then((w) => {
        const [emoji, text] = WEATHER[w.code] || ["🌡", ""];
        const e = document.createElement("span"); e.className = "emoji"; e.textContent = emoji;
        const big = document.createElement("span"); big.className = "big"; big.textContent = `${w.temp}°`;
        const info = document.createElement("span"); info.className = "info";
        const line1 = document.createElement("span"); line1.textContent = `${w.name} · ${text}`;
        const line2 = document.createElement("span"); line2.textContent = `En yüksek ${w.max}° · en düşük ${w.min}°`;
        const change = document.createElement("button"); change.className = "city-link"; change.textContent = "Şehri değiştir";
        change.addEventListener("click", () => saveWidgetsAndRender({ ...widgetData(), city: "" }));
        info.append(line1, line2, change);
        now.replaceChildren(e, big, info);
    }).catch(() => {
        now.textContent = "Hava durumu alınamadı.";
        const change = document.createElement("button"); change.className = "city-link"; change.textContent = "Şehri değiştir";
        change.addEventListener("click", () => saveWidgetsAndRender({ ...widgetData(), city: "" }));
        now.append(" ", change);
    });
    return box;
}

function noteWidget() {
    const box = card("Not");
    const area = document.createElement("textarea");
    area.placeholder = "Aklına geleni yaz…";
    area.value = widgetData().note || "";
    let timer = null;
    area.addEventListener("input", () => {
        clearTimeout(timer);
        timer = setTimeout(() => saveWidgets({ ...widgetData(), note: area.value.slice(0, 2000) }), 600);
    });
    box.append(area);
    return box;
}

function todoWidget() {
    const box = card("Yapılacaklar");
    const todos = Array.isArray(widgetData().todos) ? widgetData().todos : [];
    const list = document.createElement("ul");
    list.className = "todo";
    todos.forEach((item, index) => {
        const li = document.createElement("li");
        if (item.done) li.className = "done";
        const check = document.createElement("input");
        check.type = "checkbox";
        check.checked = Boolean(item.done);
        check.addEventListener("change", () => {
            const copy = todos.map((t, i) => i === index ? { ...t, done: check.checked } : t);
            saveWidgetsAndRender({ ...widgetData(), todos: copy });
        });
        const text = document.createElement("span");
        text.textContent = item.text;
        const remove = document.createElement("button");
        remove.textContent = "✕";
        remove.setAttribute("aria-label", "Sil");
        remove.addEventListener("click", () => saveWidgetsAndRender({ ...widgetData(), todos: todos.filter((_, i) => i !== index) }));
        li.append(check, text, remove);
        list.append(li);
    });
    const input = document.createElement("input");
    input.placeholder = "Yeni iş ekle…";
    input.enterKeyHint = "done";
    input.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && input.value.trim() && todos.length < 30)
            saveWidgetsAndRender({ ...widgetData(), todos: [...todos, { text: input.value.trim().slice(0, 120), done: false }] });
    });
    box.append(list, input);
    return box;
}

function saveWidgetsAndRender(data) {
    saveWidgets(data);
    renderWidgets();
}

function renderWidgets() {
    const parts = [];
    if (options.weather) parts.push(weatherWidget());
    if (options.todo) parts.push(todoWidget());
    if (options.note) parts.push(noteWidget());
    $("widgets").replaceChildren(...parts);
}

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
    // Not while typing a note: the page would lose the cursor.
    if (!document.activeElement || !document.activeElement.closest(".widget")) renderWidgets();
});

tick();
setInterval(tick, 10000);
renderShortcuts();
send({ type: "ready" });
