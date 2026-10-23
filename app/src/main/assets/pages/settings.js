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

let draggingZoom = false;
$("text-zoom").addEventListener("input", () => { draggingZoom = true; $("text-zoom-label").textContent = `Yazı boyutu %${$("text-zoom").value}`; });
$("text-zoom").addEventListener("change", () => { draggingZoom = false; send({ type: "setTextZoom", value: Number($("text-zoom").value) }); });
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
