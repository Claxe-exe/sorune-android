// Settings: every change goes to the app, which saves it and sends the new state back.
"use strict";

function renderSettings(state) {
    $("version").textContent = state.version || "";

    $("themes").replaceChildren(...(state.themes || []).map((theme) => {
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
        return card;
    }));

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
}

onAppMessage((message) => {
    if (message.type === "settings") renderSettings(message);
});

$("check").addEventListener("click", () => send({ type: "checkUpdate" }));

send({ type: "ready" });
send({ type: "settings:get" });
