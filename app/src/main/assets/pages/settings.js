// Settings: every change goes to the app, which saves it and sends the new state back.
"use strict";

function renderSettings(state) {
    $("version").textContent = state.version || "";

    $("themes").replaceChildren(...(state.themes || []).map((theme) => {
        const holder = document.createElement("div");
        holder.className = "theme-holder";
        const card = document.createElement("button");
        card.type = "button";
        card.className = "theme" + (theme.id === state.theme ? " selected" : "");
        card.setAttribute("aria-pressed", String(theme.id === state.theme));
        card.title = theme.description || theme.name;
        if (theme.thumb) card.style.backgroundImage = `url("${theme.thumb}")`;
        const name = document.createElement("span");
        name.className = "name";
        const dot = document.createElement("span");
        dot.className = "dot";
        dot.style.background = theme.accent;
        dot.style.color = theme.accent;
        name.append(dot, document.createTextNode(theme.name));
        card.append(name);
        card.addEventListener("click", () => { if (theme.id !== state.theme) send({ type: "setTheme", id: theme.id }); });
        holder.append(card);
        if (theme.custom) {
            const remove = document.createElement("button");
            remove.type = "button";
            remove.className = "remove";
            remove.textContent = "✕";
            remove.setAttribute("aria-label", "Temayı sil");
            remove.addEventListener("click", () => { if (confirm(`"${theme.name}" silinsin mi?`)) send({ type: "custom:delete", id: theme.id }); });
            holder.append(remove);
        }
        return holder;
    }));

    renderNewTab(state);
    for (const button of $("tabs-place").querySelectorAll("button"))
        button.setAttribute("aria-checked", String(button.dataset.value === (state.tabsPlace || "bar")));
    for (const button of $("toolbar-position").querySelectorAll("button"))
        button.setAttribute("aria-checked", String((button.dataset.value === "bottom") === Boolean(state.toolbarBottom)));
    if (!draggingZoom) $("text-zoom").value = state.textZoom || 100;
    $("text-zoom-label").textContent = `Yazı boyutu %${$("text-zoom").value}`;
    $("site-dark-row").hidden = state.siteDarkSupported === false;
    $("site-dark").setAttribute("aria-checked", String(Boolean(state.siteDark)));
    $("site-dark").onclick = () => send({ type: "setSiteDark", value: !state.siteDark });
    $("ad-block").setAttribute("aria-checked", String(Boolean(state.adBlock)));
    $("ad-block").onclick = () => send({ type: "setAdBlock", value: !state.adBlock });
    $("pip").setAttribute("aria-checked", String(Boolean(state.pip)));
    $("pip").onclick = () => send({ type: "setPip", value: !state.pip });

    $("engines").replaceChildren(...(state.engines || []).map((engine) => {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = engine.name;
        button.setAttribute("role", "radio");
        button.setAttribute("aria-checked", String(engine.id === state.engine));
        button.addEventListener("click", () => send({ type: "setEngine", id: engine.id }));
        return button;
    }));

    $("suggest").setAttribute("aria-checked", String(Boolean(state.suggest)));
    $("suggest").onclick = () => send({ type: "setSuggest", value: !state.suggest });

    $("auto").setAttribute("aria-checked", String(Boolean(state.autoScreen)));
    $("auto").onclick = () => send({ type: "setAutoScreen", value: !state.autoScreen });
    if (!draggingSeconds) $("auto-seconds").value = state.autoSeconds || 10;
    showSeconds(Number($("auto-seconds").value));
}

// ---------------------------------------------------------------- new tab

const FONT_CHOICES = [
    ["system", "Sade", "system-ui, sans-serif"],
    ["orbitron", "Orbitron", "'Orbitron', sans-serif"],
    ["bebas", "Bebas", "'Bebas Neue', sans-serif"],
    ["righteous", "Righteous", "'Righteous', sans-serif"],
    ["pacifico", "Pacifico", "'Pacifico', cursive"],
    ["script", "El yazısı", "'Dancing Script', cursive"],
    ["pixel", "Piksel", "'Press Start 2P', monospace"],
];
let draggingNewTab = null;

function setNewTab(key, value) {
    send({ type: "newtab:set", key, value });
}

function renderFonts(id, key, current, sample) {
    $(id).replaceChildren(...FONT_CHOICES.map(([value, name, family]) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "font-choice";
        button.setAttribute("aria-checked", String((current || "system") === value));
        button.setAttribute("aria-label", name);
        button.style.fontFamily = family;
        button.textContent = sample;
        button.addEventListener("click", () => setNewTab(key, value));
        return button;
    }));
}

function renderNewTab(state) {
    const nt = state.newtab || {};
    renderFonts("clock-font", "clockFont", nt.clockFont, "12:34");
    renderFonts("label-font", "labelFont", nt.labelFont, "Aa");
    for (const b of $("hour12").querySelectorAll("button")) b.setAttribute("aria-checked", String((b.dataset.value === "12") === Boolean(nt.hour12)));
    for (const b of $("nt-effect").querySelectorAll("button")) b.setAttribute("aria-checked", String(b.dataset.value === (nt.effect || "theme")));
    if (draggingNewTab !== "dim") $("nt-dim").value = nt.dim || 0;
    if (draggingNewTab !== "blur") $("nt-blur").value = nt.blur || 0;
    $("nt-dim-label").textContent = `Karartma %${$("nt-dim").value}`;
    $("nt-blur-label").textContent = `Bulanıklık ${$("nt-blur").value} px`;
    $("nt-greeting").setAttribute("aria-checked", String(nt.greeting !== false));
    $("nt-greeting").onclick = () => setNewTab("greeting", nt.greeting === false);
    $("nt-bookmarks").setAttribute("aria-checked", String(nt.bookmarks !== false));
    $("nt-bookmarks").onclick = () => setNewTab("bookmarks", nt.bookmarks === false);
    for (const [id, key] of [["w-weather", "weather"], ["w-todo", "todo"], ["w-note", "note"]]) {
        $(id).setAttribute("aria-checked", String(Boolean(nt[key])));
        $(id).onclick = () => setNewTab(key, !nt[key]);
    }
    $("translate-language").replaceChildren(...(state.languages || []).map((language) => {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = language.name;
        button.setAttribute("aria-checked", String(language.id === state.translateLanguage));
        button.addEventListener("click", () => send({ type: "setTranslateLanguage", value: language.id }));
        return button;
    }));
    $("swipe-tabs").setAttribute("aria-checked", String(Boolean(state.swipeTabs)));
    $("swipe-tabs").onclick = () => send({ type: "setSwipeTabs", value: !state.swipeTabs });
    $("pull-refresh").setAttribute("aria-checked", String(Boolean(state.pullRefresh)));
    $("pull-refresh").onclick = () => send({ type: "setPullRefresh", value: !state.pullRefresh });
}

$("hour12").addEventListener("click", (e) => { const b = e.target.closest("button"); if (b) setNewTab("hour12", b.dataset.value === "12"); });
$("nt-effect").addEventListener("click", (e) => { const b = e.target.closest("button"); if (b) setNewTab("effect", b.dataset.value); });
for (const [id, key, label] of [["nt-dim", "dim", (v) => `Karartma %${v}`], ["nt-blur", "blur", (v) => `Bulanıklık ${v} px`]]) {
    $(id).addEventListener("input", () => { draggingNewTab = key; $(id + "-label").textContent = label($(id).value); });
    $(id).addEventListener("change", () => { draggingNewTab = null; setNewTab(key, Number($(id).value)); });
}
$("nt-reset").addEventListener("click", () => {
    if (confirm("Kısayollar varsayılana dönsün mü?")) send({ type: "newtab:resetShortcuts" });
});

let draggingZoom = false;
$("text-zoom").addEventListener("input", () => { draggingZoom = true; $("text-zoom-label").textContent = `Yazı boyutu %${$("text-zoom").value}`; });
$("text-zoom").addEventListener("change", () => { draggingZoom = false; send({ type: "setTextZoom", value: Number($("text-zoom").value) }); });
$("tabs-place").addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (button) send({ type: "setTabsPlace", value: button.dataset.value });
});

$("toolbar-position").addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (button) send({ type: "setToolbarBottom", value: button.dataset.value === "bottom" });
});

// ---------------------------------------------------------------- your own theme

const creator = { accent: null, ratio: 16 / 9 };

function placePreview() {
    // The same crop the phone uses: the character (focus) kept in the middle of a 9:19 screen.
    const focus = Number($("focus").value) / 100;
    const visible = (9 / 19) / creator.ratio;
    const position = visible >= 1 ? 0.5 : Math.min(1, Math.max(0, (focus - visible / 2) / (1 - visible)));
    $("phone").style.backgroundPosition = `${(position * 100).toFixed(1)}% center`;
}

function pickAccent(color) {
    creator.accent = color;
    for (const swatch of $("palette").children) swatch.classList.toggle("selected", swatch.dataset.color === color);
    $("phone").style.setProperty("--preview-accent", color);
}

$("pick").addEventListener("click", () => send({ type: "custom:pick" }));
$("focus").addEventListener("input", placePreview);
$("save-theme").addEventListener("click", () => {
    send({ type: "custom:create", name: $("theme-name").value, accent: creator.accent, focusX: Number($("focus").value) / 100 });
    $("creator").hidden = true;
    $("theme-name").value = "";
});

onAppMessage((message) => {
    if (message.type !== "custom:picked") return;
    const image = new Image();
    image.onload = () => { creator.ratio = image.naturalWidth / image.naturalHeight; placePreview(); };
    image.src = message.preview;
    $("phone").style.backgroundImage = `url("${message.preview}")`;
    $("palette").replaceChildren(...(message.palette || []).map((color) => {
        const swatch = document.createElement("button");
        swatch.type = "button";
        swatch.className = "swatch";
        swatch.dataset.color = color;
        swatch.style.background = color;
        swatch.setAttribute("aria-label", color);
        swatch.addEventListener("click", () => pickAccent(color));
        return swatch;
    }));
    pickAccent((message.palette || [])[0] || "#B583FF");
    $("focus").value = 50;
    $("creator").hidden = false;
    $("creator").scrollIntoView({ behavior: "smooth", block: "center" });
});

let draggingSeconds = false;
function showSeconds(seconds) {
    $("auto-seconds-label").textContent = `Her ${seconds} saniyede bak`;
}
$("auto-seconds").addEventListener("input", () => { draggingSeconds = true; showSeconds(Number($("auto-seconds").value)); });
$("auto-seconds").addEventListener("change", () => {
    draggingSeconds = false;
    send({ type: "setAutoSeconds", value: Number($("auto-seconds").value) });
});

onAppMessage((message) => {
    if (message.type === "settings") renderSettings(message);
});

$("check").addEventListener("click", () => send({ type: "checkUpdate" }));

send({ type: "ready" });
send({ type: "settings:get" });
