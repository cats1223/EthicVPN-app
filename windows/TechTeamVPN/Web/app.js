// TechTeamVPN for Windows: the page inside the WebView2 window. The C# side (Bridge.cs) owns the
// VPN; this draws the phone apps' interface and sends it commands. Opened in a plain browser,
// without chrome.webview, it runs on demo data, which is how the design is checked.
"use strict";

const host = window.chrome && window.chrome.webview;
const $ = (id) => document.getElementById(id);

// ---------- Bridge ----------

const send = (cmd, data = {}) => {
  if (host) host.postMessage(JSON.stringify({ cmd, ...data }));
  else demo.handle(cmd, data);
};

let state = { profiles: [], selectedId: null, status: "disconnected", settings: {}, subscription: null, since: 0 };
let pings = {}, locations = { home: null, visible: null, servers: {} };
let traffic = { down: 0, up: 0, session: 0, blocked: 0 };

function onMessage(msg) {
  switch (msg.type) {
    case "state": {
      const before = state.status;
      state = msg.state;
      render();
      if (state.status === "connected" && before === "connecting") burst();
      break;
    }
    case "pings": pings = msg.pings; renderChips(); renderServerList(); break;
    case "locations": locations = msg; renderStatus(); updateMap(); break;
    case "traffic": traffic = msg; renderTraffic(); break;
    case "toast": toast(msg.text); break;
    case "clipboard": $("addText").value = msg.text || ""; break;
    case "update":
      if (confirm(`Вышла версия ${msg.version}, у вас ${state.version}. Скачать?`)) send("openUrl", { url: msg.url });
      break;
  }
}
if (host) host.addEventListener("message", (e) => onMessage(typeof e.data === "string" ? JSON.parse(e.data) : e.data));

// ---------- Helpers ----------

const ICONS = {
  shieldOk: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2 4 5v6c0 5 3.4 9.7 8 11 4.6-1.3 8-6 8-11V5l-8-3Zm-1.2 14.2-3.5-3.5 1.4-1.4 2.1 2.1 4.6-4.6 1.4 1.4-6 6Z"/></svg>',
  shield: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2 4 5v6c0 5 3.4 9.7 8 11 4.6-1.3 8-6 8-11V5l-8-3Z"/></svg>',
  warn: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 3 2 20h20L12 3Z"/><path d="M12 10v4M12 17.5v.5" stroke-linecap="round"/></svg>',
  server: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01" stroke-width="3" stroke-linecap="round"/></svg>',
  plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8" stroke-linecap="round"/></svg>',
  speed: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 16a8 8 0 1 1 16 0"/><path d="m12 16 4-5"/></svg>',
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/></svg>',
  chevron: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="m9 6 6 6-6 6"/></svg>',
};
document.querySelectorAll("i[data-icon]").forEach((i) => (i.innerHTML = ICONS[i.dataset.icon]));

function formatBytes(n) {
  const units = ["Б", "КБ", "МБ", "ГБ", "ТБ"];
  let v = n, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return i === 0 ? `${n} ${units[0]}` : `${v.toFixed(1).replace(".", ",")} ${units[i]}`;
}
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const leadingFlag = (name) => {
  const cps = [...(name || "")];
  const isRI = (c) => c && c.codePointAt(0) >= 0x1f1e6 && c.codePointAt(0) <= 0x1f1ff;
  return isRI(cps[0]) && isRI(cps[1]) ? cps[0] + cps[1] : null;
};
const stripFlag = (name) => (leadingFlag(name) ? [...name].slice(2).join("").trim() : name);
const flagFor = (p) => leadingFlag(p.name) || locations.servers[p.address]?.flag || "🌐";
const pingClass = (ms) => (ms <= 0 ? "bad" : ms < 150 ? "good" : ms < 400 ? "warn" : "bad");

let toastTimer;
function toast(text) {
  const t = $("toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 2600);
}

// ---------- Rendering ----------

const selected = () => state.profiles.find((p) => p.id === state.selectedId) || state.profiles[0];

function render() {
  const s = state.settings || {};
  document.documentElement.classList.toggle("light", s.theme === "light");
  renderStatus();
  renderTraffic();
  renderChips();
  renderSub();
  renderServerList();
  if (!$("sheet-settings").hidden) renderSettings();
  updateMap();
}

let timer;
function renderStatus() {
  const st = state.status;
  const connected = st === "connected", connecting = st === "connecting";
  const pill = $("pill");
  pill.className = "pill glass " + st;
  pill.querySelector(".pill-text").textContent =
    connected ? "Подключено" : connecting ? "Подключение…" : st === "error" ? "Ошибка подключения" : "Не подключено";

  const card = $("statusCard");
  card.className = "status-card " + st;
  const icon = connected ? "shieldOk" : connecting ? "shield" : "warn";
  if ($("shield").dataset.icon !== icon) { $("shield").innerHTML = ICONS[icon]; $("shield").dataset.icon = icon; }
  $("statusTitle").textContent = connected ? "Вы защищены" : connecting ? "Подключение…" : "Вы не защищены";

  clearInterval(timer);
  const sub = () => {
    if (st === "error" && state.error) return state.error;
    if (connected) {
      const s = Math.max(0, Math.floor((Date.now() - state.since) / 1000));
      const hms = [s / 3600, (s % 3600) / 60, s % 60].map((x) => String(Math.floor(x)).padStart(2, "0")).join(":");
      return `${line(locations.visible, "Сайты видят")} · ${hms}`;
    }
    if (locations.home) return line(locations.home, "Ваш адрес");
    return "Подключитесь к VPN для защиты";
  };
  $("statusSub").textContent = sub();
  if (connected) timer = setInterval(() => ($("statusSub").textContent = sub()), 1000);

  const btn = $("connect");
  if (!state.profiles.length) { btn.textContent = "Добавить ключ"; btn.className = "big"; }
  else if (connected) { btn.textContent = "Отключить"; btn.className = "big stop"; }
  else if (connecting) { btn.textContent = "Отмена"; btn.className = "big stop"; }
  else { btn.textContent = "Подключить"; btn.className = "big"; }
  $("traffic").hidden = !connected;
}
const line = (loc, prefix) => (loc ? `${prefix}: ${loc.flag} ${loc.place}` : `${prefix}: определяю…`);

function burst() {
  const s = $("shield");
  s.classList.remove("burst");
  void s.offsetWidth;
  s.classList.add("burst");
}

function renderTraffic() {
  $("down").textContent = `↓ ${formatBytes(Math.round(traffic.down))}/с`;
  $("up").textContent = `↑ ${formatBytes(Math.round(traffic.up))}/с`;
  $("session").textContent = formatBytes(traffic.session || 0);
}

function renderChips() {
  $("serversBlock").hidden = !state.profiles.length;
  const sel = selected();
  $("chips").innerHTML = state.profiles.map((p) => {
    const ms = pings[p.id];
    const pingText = ms === undefined ? "" : `<span class="ms ${pingClass(ms)}">${ms > 0 ? ms + " мс" : "—"}</span>`;
    return `<button class="chip ${p.id === sel?.id ? "selected" : ""}" data-id="${p.id}">
      <span class="flag">${flagFor(p)}</span><span>${esc(stripFlag(p.name))}</span>${pingText}</button>`;
  }).join("");
}

function renderSub() {
  const sub = state.subscription, info = sub?.info;
  const card = $("subCard");
  card.hidden = !info;
  if (!info) return;
  const used = info.upload + info.download;
  const parts = [`<div class="title">${esc(info.title || "Подписка")}</div>`];
  if (info.total > 0) {
    parts.push(`<div class="bar"><div style="width:${Math.min(100, (used / info.total) * 100)}%"></div></div>`);
    parts.push(`<div class="muted">Трафик: ${formatBytes(used)} из ${formatBytes(info.total)}</div>`);
  } else if (used > 0) parts.push(`<div class="muted">Использовано: ${formatBytes(used)}</div>`);
  if (info.expire > 0) parts.push(`<div class="muted">Осталось дней: ${Math.max(0, Math.floor((info.expire * 1000 - Date.now()) / 86400000))}</div>`);
  // A message the panel sends to everyone (Remnawave "announce").
  if (info.announce) parts.push(`<div style="color:var(--accent)">📣 ${esc(info.announce)}</div>`);
  card.innerHTML = parts.join("");
}

function renderServerList() {
  const sel = selected();
  $("serverList").innerHTML = state.profiles.length
    ? state.profiles.map((p) => {
        const ms = pings[p.id];
        return `<div class="item ${p.id === sel?.id ? "selected" : ""}" data-id="${p.id}">
          <span class="flag">${flagFor(p)}</span>
          <div class="name">${esc(stripFlag(p.name))}<small>${esc(p.network.toUpperCase())} · ${esc(p.security.toUpperCase())}</small></div>
          <span class="ms ${ms === undefined ? "" : pingClass(ms)}">${ms === undefined ? "" : ms > 0 ? ms + " мс" : "—"}</span>
          <button class="del" data-del="${p.id}" title="Удалить">✕</button></div>`;
      }).join("")
    : `<div class="item"><div class="name muted">Серверов пока нет. Добавьте ключ или подписку.</div></div>`;
  $("removeSub").hidden = !state.subscription;
}

// ---------- Settings ----------

const DNS = [["", "Автоматически", "Cloudflare и Google"], ["cloudflare", "Cloudflare", "1.1.1.1 — быстрый"], ["google", "Google", "8.8.8.8"],
  ["adguard", "AdGuard", "Ещё и блокирует рекламу"], ["custom", "Свой", "Адрес IPv4 или https://…/dns-query"]];
// The everyday settings first; protection, DNS, own sites and the rest one level down.
let advanced = false;

function renderSettings() {
  const s = state.settings || {};
  const toggle = (key, title, hint = "") =>
    `<div class="setting" data-toggle="${key}"><div class="text">${title}${hint ? `<small>${hint}</small>` : ""}</div><div class="switch ${s[key] ? "on" : ""}"></div></div>`;
  const seg = (key, options) =>
    `<div class="segmented">${options.map(([v, t]) => `<button data-set="${key}" data-value="${v}" class="${(s[key] || "") === v ? "on" : ""}">${t}</button>`).join("")}</div>`;
  const link = (attr, title, value = "›") => `<div class="setting" ${attr}><div class="text">${title}</div><div class="value">${value}</div></div>`;
  const html = [];
  $("settingsTitle").textContent = advanced ? "Дополнительно" : "Настройки";
  $("settingsBack").hidden = !advanced;

  if (!advanced) {
    html.push(`<div class="section">Оформление</div>`, seg("theme", [["dark", "Чёрная"], ["light", "Белая"]]));
    html.push(`<div class="section">Режим</div>`, seg("mode", [["proxy", "Системный прокси"], ["tun", "Весь трафик (TUN)"]]));
    html.push(`<p class="hint">${s.mode === "tun"
      ? (state.isAdmin ? "Весь трафик компьютера идёт через VPN, включая игры и программы без поддержки прокси." : "Для этого режима нужны права администратора — приложение перезапустится с ними при подключении.")
      : "Браузеры и большинство программ идут через VPN без прав администратора. Игры и программы, которые не смотрят на системный прокси, — мимо."}</p>`);
    html.push(`<div class="section">Раздельное туннелирование</div>`, toggle("russiaDirect", "Российские сайты напрямую", "Зоны .ru, .su, .рф и российские адреса — без VPN"));
    html.push(`<div class="section">Подключение</div>`,
      toggle("autoFastest", "Самый быстрый сервер", "Перед подключением проверить пинг всех серверов"),
      toggle("autoReconnect", "Автопереподключение", "Если VPN оборвался, подключиться снова"),
      toggle("autostart", "Запускать вместе с Windows"),
      toggle("connectOnStart", "Подключаться при запуске"));
    html.push(`<div class="section"></div>`, link("data-advanced", "Дополнительно<small>Защита, DNS, свои сайты, тест скорости, обновления</small>"));
    html.push(`<div class="section">О приложении</div>`,
      `<div class="setting"><div class="text">Версия</div><div class="muted">${esc(state.version || "")}</div></div>`,
      `<div class="setting" data-cmd="checkUpdate"><div class="text value">Проверить обновления</div></div>`);
  } else {
    html.push(`<div class="section">Защита</div>`, toggle("blockAds", "Блокировать рекламу", "Рекламные и следящие домены не загружаются. Если сайт сломался — добавьте его в «Свои сайты»"));
    if (s.mode === "tun") html.push(toggle("killSwitch", "Блокировать утечки", "DNS — только через VPN, IPv6 выключен, чтобы ничего не ушло мимо"));
    html.push(link("data-open-dns", "DNS", (DNS.find((d) => d[0] === (s.dns || "")) || DNS[0])[1]));
    if (dnsOpen) {
      html.push(`<div class="list">${DNS.map(([v, t, h]) => `<div class="item ${(s.dns || "") === v ? "selected" : ""}" data-dns="${v}"><div class="name">${t}<small>${h}</small></div></div>`).join("")}</div>`);
      if (s.dns === "custom") html.push(`<input type="text" id="customDns" placeholder="9.9.9.9" value="${esc(s.customDns || "")}">`);
    }
    html.push(`<div class="section">Свои сайты — по одному в строке</div>`,
      `<div class="sites"><p class="hint">Всегда напрямую</p><textarea id="directSites" placeholder="bank.ru">${esc((s.directSites || []).join("\n"))}</textarea>
       <p class="hint">Всегда через VPN</p><textarea id="proxySites" placeholder="example.com">${esc((s.proxySites || []).join("\n"))}</textarea></div>`);
    html.push(`<div class="section">Ещё</div>`, link("data-open-speed", "Тест скорости"));
    html.push(`<div class="section">Обновления</div>`, toggle("betaUpdates", "Получать бета-версии"));
  }
  $("settings").innerHTML = html.join("");
}
let dnsOpen = false;

$("settings").addEventListener("click", (e) => {
  const s = state.settings;
  const t = e.target.closest("[data-toggle],[data-set],[data-advanced],[data-open-speed],[data-open-dns],[data-dns],[data-url],[data-cmd]");
  if (!t) return;
  if (t.dataset.toggle) setSetting(t.dataset.toggle, !s[t.dataset.toggle]);
  else if (t.dataset.set) setSetting(t.dataset.set, t.dataset.value);
  else if (t.hasAttribute("data-advanced")) { advanced = true; renderSettings(); $("sheet-settings").scrollTop = 0; }
  else if (t.hasAttribute("data-open-speed")) openSheet("speed");
  else if (t.hasAttribute("data-open-dns")) { dnsOpen = !dnsOpen; renderSettings(); }
  else if (t.dataset.dns !== undefined) setSetting("dns", t.dataset.dns);
  else if (t.dataset.url) send("openUrl", { url: t.dataset.url });
  else if (t.dataset.cmd) send(t.dataset.cmd, { manual: true });
});
$("settings").addEventListener("change", (e) => {
  if (e.target.id === "customDns") setSetting("customDns", e.target.value.trim());
  if (e.target.id === "directSites" || e.target.id === "proxySites")
    setSetting(e.target.id, e.target.value.split("\n").map((x) => x.trim()).filter(Boolean));
});
function setSetting(key, value) {
  state.settings = { ...state.settings, [key]: value };
  render();
  renderSettings();
  send("setSetting", { key, value });
}

// ---------- Panel and sheets ----------

$("handle").onclick = () => $("panel").classList.toggle("expanded");
let dragY = null;
$("panel").addEventListener("wheel", (e) => { if (Math.abs(e.deltaY) > 20) $("panel").classList.toggle("expanded", e.deltaY < 0 || $("panel").scrollTop > 0); }, { passive: true });
$("panel").addEventListener("pointerdown", (e) => (dragY = e.target.closest(".chips, button, textarea") ? null : e.clientY));
window.addEventListener("pointerup", (e) => {
  if (dragY !== null && Math.abs(e.clientY - dragY) > 30) $("panel").classList.toggle("expanded", e.clientY < dragY);
  dragY = null;
});

$("connect").onclick = () => {
  if (!state.profiles.length) return openSheet("add");
  send(state.status === "connected" || state.status === "connecting" ? "disconnect" : "connect");
};
$("chips").onclick = (e) => { const c = e.target.closest(".chip"); if (c) send("select", { id: c.dataset.id }); };
$("serverList").onclick = (e) => {
  const del = e.target.closest("[data-del]");
  if (del) return send("remove", { id: del.dataset.del });
  const item = e.target.closest(".item[data-id]");
  if (item) send("select", { id: item.dataset.id });
};
document.querySelectorAll("[data-open]").forEach((b) => (b.onclick = () => openSheet(b.dataset.open)));
document.querySelectorAll(".sheet .close").forEach((b) => (b.onclick = closeSheets));
$("scrim").onclick = closeSheets;
window.addEventListener("keydown", (e) => e.key === "Escape" && closeSheets());

function openSheet(name) {
  closeSheets();
  $("scrim").hidden = false;
  $("sheet-" + name).hidden = false;
  if (name === "settings") { advanced = false; renderSettings(); }
  if (name === "servers") send("pingAll");
  if (name === "speed") $("speedVia").textContent = state.status === "connected" ? "Замер идёт через VPN." : "VPN выключен — замер идёт напрямую.";
  if (name === "add") $("addText").focus();
}
function closeSheets() {
  document.querySelectorAll(".sheet").forEach((s) => (s.hidden = true));
  $("scrim").hidden = true;
}

$("paste").onclick = () => send("readClipboard");
$("addSave").onclick = () => {
  const text = $("addText").value.trim();
  if (!text) return;
  send("add", { text });
  $("addText").value = "";
  closeSheets();
};
$("pingAll").onclick = () => send("pingAll");
$("serversAdd").onclick = () => openSheet("add");
$("settingsBack").onclick = () => { advanced = false; renderSettings(); };
$("refreshSub").onclick = () => send("refresh");
$("removeSub").onclick = () => confirm("Удалить подписку и её серверы?") && send("removeSubscription");

// ---------- Globe ----------

const DARK_STYLE = "https://tiles.openfreemap.org/styles/dark";
const LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty";
let map, mapReady = false, mapStyle = null, lastFocus = null;

async function initMap() {
  if (!window.maplibregl) return;
  const url = state.settings?.theme === "light" ? LIGHT_STYLE : DARK_STYLE;
  mapStyle = url;
  // The OpenFreeMap style with the globe projection added: a sphere when zoomed out.
  let style = url;
  try { style = await (await fetch(url)).json(); style.projection = { type: "globe" }; } catch { }
  map = new maplibregl.Map({ container: "map", style, center: [37.62, 55.75], zoom: 1.6, attributionControl: { compact: true } });
  map.on("load", () => {
    map.addSource("route", { type: "geojson", data: empty() });
    map.addSource("pins", { type: "geojson", data: empty() });
    map.addSource("packets", { type: "geojson", data: empty() });
    map.addSource("halo", { type: "geojson", data: empty() });
    const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim();
    map.addLayer({ id: "route", type: "line", source: "route", paint: { "line-color": accent, "line-width": 3, "line-opacity": 0.45 } });
    map.addLayer({ id: "packets", type: "circle", source: "packets",
      paint: { "circle-radius": ["get", "r"], "circle-opacity": ["get", "o"], "circle-color": ["get", "c"], "circle-blur": 0.4 } });
    map.addLayer({ id: "halo", type: "circle", source: "halo", paint: { "circle-radius": 8, "circle-color": "#2bd98b", "circle-opacity": 0 } });
    map.addLayer({ id: "pins", type: "circle", source: "pins",
      paint: { "circle-radius": 7, "circle-color": ["get", "color"], "circle-stroke-color": "#fff", "circle-stroke-width": 2 } });
    mapReady = true;
    updateMap();
    requestAnimationFrame(animate);
  });
  map.on("render", placeLabels);
}
const empty = () => ({ type: "FeatureCollection", features: [] });
const point = (lon, lat, props = {}) => ({ type: "Feature", geometry: { type: "Point", coordinates: [lon, lat] }, properties: props });

function pins() {
  const connected = state.status === "connected";
  const sel = selected();
  const server = sel && locations.servers[sel.address];
  const out = [];
  if (locations.home) out.push({ ...locations.home, label: connected ? "Реальный адрес" : "Вы здесь", active: !connected });
  if (server) out.push({ ...server, label: connected ? "Вы сейчас здесь" : stripFlag(sel.name), active: connected });
  return out;
}

function updateMap() {
  if (!mapReady) return;
  const wanted = state.settings?.theme === "light" ? LIGHT_STYLE : DARK_STYLE;
  if (wanted !== mapStyle) { mapReady = false; map.remove(); initMap(); return; }
  const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim();
  map.setPaintProperty("route", "line-color", accent);
  const ps = pins();
  map.getSource("pins").setData({ type: "FeatureCollection",
    features: ps.map((p) => point(p.longitude, p.latitude, { color: p.active ? "#2bd98b" : "#ffffff" })) });
  const connected = state.status === "connected";
  map.getSource("route").setData(connected && ps.length === 2
    ? { type: "FeatureCollection", features: [{ type: "Feature", geometry: { type: "LineString", coordinates: ps.map((p) => [p.longitude, p.latitude]) } }] }
    : empty());
  const active = ps.find((p) => p.active);
  map.getSource("halo").setData(active ? { type: "FeatureCollection", features: [point(active.longitude, active.latitude)] } : empty());
  // Fly only when the place changes, so the user's own panning is left alone.
  const focus = connected ? ps.find((p) => p.active) || ps[0] : ps[0];
  const key = focus ? `${focus.latitude},${focus.longitude}` : null;
  if (focus && key !== lastFocus) {
    lastFocus = key;
    // Padding keeps the place above the panel instead of under it.
    const panel = $("panel").getBoundingClientRect();
    map.flyTo({ center: [focus.longitude, focus.latitude], zoom: 2.4, duration: 1600,
      padding: { top: 60, bottom: Math.min(panel.height, innerHeight * 0.6), left: 0, right: 0 } });
  }
  placeLabels();
}

function placeLabels() {
  if (!mapReady) return;
  $("labels").innerHTML = pins().map((p) => {
    const xy = map.project([p.longitude, p.latitude]);
    return `<div class="map-label ${p.active ? "active" : ""}" style="left:${xy.x}px;top:${xy.y}px">${esc(p.label)}</div>`;
  }).join("");
}

// Comets from the computer to the server, and a ring spreading out from the active pin.
const COMETS = 3, TAIL = 4, TRIP = 4500, HALO = 1600;
function alongMercator(a, b, f) {
  const y = (lat) => Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360));
  const my = y(a.latitude) + (y(b.latitude) - y(a.latitude)) * f;
  return [a.longitude + (b.longitude - a.longitude) * f, ((2 * Math.atan(Math.exp(my)) - Math.PI / 2) * 180) / Math.PI];
}
function animate(now) {
  if (!mapReady) return;
  const ps = pins();
  const connected = state.status === "connected" && ps.length === 2;
  const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim();
  const features = [];
  if (connected) {
    const t = (now % TRIP) / TRIP;
    for (let k = 0; k < COMETS; k++) for (let rank = 0; rank <= TAIL; rank++) {
      let f = t + k / COMETS - rank * 0.025;
      f -= Math.floor(f);
      const [lon, lat] = alongMercator(ps[0], ps[1], f);
      features.push(point(lon, lat, { r: rank === 0 ? 6 : 5 - rank, o: rank === 0 ? 1 : 0.85 - rank * 0.17, c: rank === 0 ? "#ffffff" : accent }));
    }
  }
  map.getSource("packets").setData({ type: "FeatureCollection", features });
  const p = (now % HALO) / HALO;
  map.setPaintProperty("halo", "circle-radius", 8 + 22 * p);
  map.setPaintProperty("halo", "circle-opacity", 0.5 * (1 - p));
  requestAnimationFrame(animate);
}

// ---------- Speed test (in the page; with the VPN on it goes through it) ----------

const MARKS = [0, 5, 10, 25, 50, 100, 250, 500];
const gaugePos = (v) => {
  if (v <= 0) return 0;
  if (v >= 500) return 1;
  const i = MARKS.findLastIndex((m) => m <= v);
  return (i + (v - MARKS[i]) / (MARKS[i + 1] - MARKS[i])) / (MARKS.length - 1);
};
let shown = 0;
function drawGauge(value) {
  const target = gaugePos(value);
  const step = () => {
    shown += (target - shown) * 0.2;
    const a0 = 135, sweep = 270, cx = 130, cy = 130, r = 112;
    const pt = (deg, rr) => [cx + Math.cos((deg * Math.PI) / 180) * rr, cy + Math.sin((deg * Math.PI) / 180) * rr];
    const arc = (f) => {
      const [x0, y0] = pt(a0, r), [x1, y1] = pt(a0 + sweep * Math.max(f, 0.0001), r);
      return `M${x0} ${y0} A${r} ${r} 0 ${sweep * f > 180 ? 1 : 0} 1 ${x1} ${y1}`;
    };
    const [nx, ny] = pt(a0 + sweep * shown, r - 38);
    $("gauge").innerHTML = `
      <path d="${arc(1)}" stroke="var(--surface-high)" stroke-width="18" fill="none" stroke-linecap="round"/>
      <path d="${arc(shown)}" stroke="var(--accent)" stroke-width="18" fill="none" stroke-linecap="round" style="filter:drop-shadow(0 0 8px var(--accent))"/>
      ${MARKS.map((m, i) => { const [x, y] = pt(a0 + (sweep * i) / (MARKS.length - 1), r - 28); return `<text x="${x}" y="${y + 4}" fill="var(--muted)" font-size="11" text-anchor="middle">${m}</text>`; }).join("")}
      <line x1="${cx}" y1="${cy}" x2="${nx}" y2="${ny}" stroke="#fff" stroke-width="4" stroke-linecap="round"/>
      <circle cx="${cx}" cy="${cy}" r="7" fill="#fff"/>`;
    $("gaugeValue").textContent = value >= 100 ? value.toFixed(0) : value.toFixed(1).replace(".", ",");
    if (Math.abs(target - shown) > 0.002) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}
drawGauge(0);

const BASE = "https://speed.cloudflare.com";
async function measure(upload) {
  let bytes = 0;
  const end = performance.now() + 8000, controllers = [];
  const stream = async () => {
    while (performance.now() < end) {
      const ctl = new AbortController(); controllers.push(ctl);
      try {
        if (upload) {
          const chunk = new Uint8Array(4_000_000);
          await fetch(BASE + "/__up", { method: "POST", body: chunk, signal: ctl.signal });
          bytes += chunk.length;
        } else {
          const res = await fetch(BASE + "/__down?bytes=50000000", { signal: ctl.signal, cache: "no-store" });
          const reader = res.body.getReader();
          while (performance.now() < end) { const { done, value } = await reader.read(); if (done) break; bytes += value.length; }
          reader.cancel().catch(() => {});
        }
      } catch { if (performance.now() < end) await new Promise((r) => setTimeout(r, 300)); }
    }
  };
  const begin = performance.now();
  const workers = [0, 1, 2, 3].map(stream);
  let last = 0, lastT = begin;
  const tick = setInterval(() => {
    const now = performance.now();
    drawGauge(((bytes - last) * 8) / ((now - lastT) / 1000) / 1e6);
    last = bytes; lastT = now;
  }, 500);
  await new Promise((r) => setTimeout(r, 8000));
  clearInterval(tick);
  controllers.forEach((c) => c.abort());
  await Promise.allSettled(workers);
  drawGauge(0);
  return (bytes * 8) / ((performance.now() - begin) / 1000) / 1e6;
}
$("speedStart").onclick = async () => {
  const btn = $("speedStart");
  btn.disabled = true;
  ["resPing", "resDown", "resUp"].forEach((id) => ($(id).textContent = "—"));
  const fmt = (v) => (v >= 100 ? v.toFixed(0) : v.toFixed(1).replace(".", ","));
  try {
    $("speedPhase").textContent = "Измеряю пинг…";
    let best = Infinity;
    for (let i = 0; i < 5; i++) {
      const t = performance.now();
      try { await fetch(BASE + "/__down?bytes=0", { cache: "no-store" }); best = Math.min(best, performance.now() - t); } catch { }
    }
    if (!isFinite(best)) throw new Error();
    $("resPing").textContent = Math.round(best);
    $("speedPhase").textContent = "Загрузка…";
    $("resDown").textContent = fmt(await measure(false));
    $("speedPhase").textContent = "Отдача…";
    $("resUp").textContent = fmt(await measure(true));
    $("speedPhase").textContent = "Готово";
  } catch {
    $("speedPhase").textContent = "Сервер теста скорости не отвечает";
  }
  btn.textContent = "Ещё раз";
  btn.disabled = false;
};

// ---------- Start ----------

const demo = {
  handle(cmd, data) {
    const s = state;
    if (cmd === "ready") {
      onMessage({ type: "state", state: {
        version: "1.3.0 (демо)", isAdmin: false, status: "disconnected", since: 0, selectedId: "nl",
        profiles: [
          { id: "nl", name: "🇳🇱 Нидерланды", address: "nl", network: "xhttp", security: "reality" },
          { id: "de", name: "🇩🇪 Германия", address: "de", network: "tcp", security: "reality" },
          { id: "fi", name: "🇫🇮 Финляндия", address: "fi", network: "ws", security: "tls" },
        ],
        subscription: { url: "https://panel", info: { title: "TechTeam · Премиум", upload: 2.1e9, download: 14.6e9, total: 107e9, expire: Date.now() / 1000 + 21 * 86400 } },
        settings: { theme: "dark", mode: "proxy", russiaDirect: true, blockAds: true, autoReconnect: true, killSwitch: true, dns: "" },
      } });
      onMessage({ type: "locations", home: { flag: "🇷🇺", place: "Москва, Россия", latitude: 55.75, longitude: 37.62 },
        visible: { flag: "🇳🇱", place: "Амстердам, Нидерланды", latitude: 52.37, longitude: 4.89 },
        servers: { nl: { flag: "🇳🇱", place: "Амстердам", latitude: 52.37, longitude: 4.89 }, de: { flag: "🇩🇪", place: "Франкфурт", latitude: 50.11, longitude: 8.68 },
          fi: { flag: "🇫🇮", place: "Хельсинки", latitude: 60.17, longitude: 24.94 } } });
      onMessage({ type: "pings", pings: { nl: 48, de: 63, fi: 212 } });
    } else if (cmd === "connect") {
      onMessage({ type: "state", state: { ...s, status: "connecting" } });
      setTimeout(() => onMessage({ type: "state", state: { ...state, status: "connected", since: Date.now() } }), 1200);
      setInterval(() => state.status === "connected" && onMessage({ type: "traffic", down: Math.random() * 3e6, up: Math.random() * 4e5, session: (traffic.session || 0) + 2e6, blocked: (traffic.blocked || 0) + 1 }), 1000);
    } else if (cmd === "disconnect") onMessage({ type: "state", state: { ...s, status: "disconnected" } });
    else if (cmd === "select") onMessage({ type: "state", state: { ...s, selectedId: data.id } });
    else if (cmd === "setSetting") onMessage({ type: "state", state: { ...s } });
  },
};

setTimeout(() => $("splash").classList.add("gone"), 1400);
setTimeout(() => $("splash").remove(), 2100);
initMap();
send("ready");
