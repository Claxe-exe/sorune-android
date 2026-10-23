// Library: history, bookmarks and downloads. The app sends the lists; changes go back to it.
"use strict";

let view = (location.hash || "#gecmis").slice(1);
let downloadTimer = null;

function showView(name) {
    view = ["gecmis", "yer-imleri", "indirmeler"].includes(name) ? name : "gecmis";
    history.replaceState(null, "", "#" + view);
    for (const tab of $("tabs").querySelectorAll("button")) tab.setAttribute("aria-selected", String(tab.dataset.view === view));
    $("search").hidden = view !== "gecmis";
    $("clear").hidden = view !== "gecmis";
    $("folder").hidden = view !== "indirmeler";
    clearInterval(downloadTimer);
    // Downloads move: refreshed every second while shown.
    if (view === "indirmeler") downloadTimer = setInterval(request, 1000);
    request();
}

function request() {
    send({ type: "library:get", view, query: $("search").value });
}

function icon(url) {
    const img = document.createElement("img");
    img.alt = "";
    try { img.src = `https://www.google.com/s2/favicons?sz=32&domain=${new URL(url).hostname}`; } catch { /* not a URL */ }
    return img;
}

function row(title, sub, url, onRemove, extra) {
    const li = document.createElement("li");
    li.className = "item";
    if (url) li.append(icon(url));
    const text = document.createElement("div");
    text.className = "text";
    const t = document.createElement("span");
    t.className = "title";
    t.textContent = title;
    const s = document.createElement("span");
    s.className = "sub";
    s.textContent = sub;
    text.append(t, s);
    if (extra) text.append(extra);
    li.append(text);
    if (onRemove) {
        const x = document.createElement("button");
        x.type = "button";
        x.className = "x";
        x.textContent = "✕";
        x.setAttribute("aria-label", "Kaldır");
        x.addEventListener("click", (event) => { event.stopPropagation(); onRemove(); });
        li.append(x);
    }
    return li;
}

function dayLabel(time) {
    const date = new Date(time);
    const today = new Date();
    const yesterday = new Date(Date.now() - 86400000);
    if (date.toDateString() === today.toDateString()) return "Bugün";
    if (date.toDateString() === yesterday.toDateString()) return "Dün";
    return date.toLocaleDateString("tr-TR", { day: "numeric", month: "long", weekday: "long" });
}

function size(bytes) {
    if (!(bytes > 0)) return "";
    return bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function render(message) {
    if (message.view !== view) return;
    const list = $("list");
    const items = message.items || [];
    const nodes = [];

    if (view === "gecmis") {
        let lastDay = "";
        for (const item of items) {
            const day = dayLabel(item.time);
            if (day !== lastDay) {
                const h = document.createElement("li");
                h.className = "day";
                h.textContent = day;
                nodes.push(h);
                lastDay = day;
            }
            const time = new Date(item.time).toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" });
            const li = row(item.title || item.url, `${time} · ${item.url}`, item.url, () => send({ type: "history:remove", url: item.url, time: item.time }));
            li.addEventListener("click", () => send({ type: "open", url: item.url }));
            nodes.push(li);
        }
    } else if (view === "yer-imleri") {
        for (const item of items) {
            const li = row(item.title || item.url, item.url, item.url, () => send({ type: "bookmark:remove", url: item.url }));
            li.addEventListener("click", () => send({ type: "open", url: item.url }));
            nodes.push(li);
        }
    } else {
        for (const item of items) {
            let sub;
            let extra = null;
            if (item.state === "running") {
                const percent = item.total > 0 ? Math.round(item.done / item.total * 100) : null;
                sub = percent === null ? "İndiriliyor…" : `İndiriliyor · %${percent} · ${size(item.done)} / ${size(item.total)}`;
                extra = document.createElement("div");
                extra.className = "track";
                const bar = document.createElement("div");
                bar.className = "bar";
                bar.style.width = (percent ?? 30) + "%";
                extra.append(bar);
            } else {
                sub = item.state === "done" ? `Bitti · ${size(item.total)}` : item.state === "failed" ? "Başarısız" : "Bulunamadı";
            }
            const li = row(item.name, sub, null, () => send({ type: "download:remove", id: item.id }), extra);
            if (item.state === "done") li.addEventListener("click", () => send({ type: "download:open", id: item.id }));
            nodes.push(li);
        }
    }

    list.replaceChildren(...nodes);
    const empty = {
        gecmis: $("search").value ? "Aramayla eşleşen sayfa yok." : "Geçmiş boş.",
        "yer-imleri": "Henüz yer imi yok. Bir sayfada ⋮ › Yer imlerine ekle.",
        indirmeler: "Henüz indirme yok.",
    }[view];
    $("empty").textContent = empty;
    $("empty").hidden = items.length > 0;
}

onAppMessage((message) => {
    if (message.type === "library") render(message);
    if (message.type === "library:changed") request();
});

$("tabs").addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (button) showView(button.dataset.view);
});
let searchTimer = null;
$("search").addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(request, 200); });
$("clear").addEventListener("click", () => {
    if (confirm("Tüm geçmiş silinsin mi?")) send({ type: "history:clear" });
});
$("folder").addEventListener("click", () => send({ type: "download:folder" }));
addEventListener("hashchange", () => showView(location.hash.slice(1)));

send({ type: "ready" });
showView(view);
