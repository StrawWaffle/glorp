// ==UserScript==
// @name         Glorp
// @namespace    https://strawwaffle.github.io/glorp/
// @version      2.1
// @description  Glorp's own chat features in destiny.gg's chat on a computer: themes, name icons, hiding other streams' chat, sticky mentions, settings synced with the Glorp app, and signing Glorp in without the developer tools.
// @match        https://www.destiny.gg/*
// @match        https://www.omniliberal.dev/*
// @grant        GM_cookie
// @grant        GM.cookie
// @grant        GM.getValue
// @grant        GM.setValue
// @grant        GM.xmlHttpRequest
// @grant        GM_registerMenuCommand
// @connect      netlify.app
// @connect      cdn.destiny.gg
// @connect      cdn.omniliberal.dev
// @require      https://raw.githubusercontent.com/StrawWaffle/glorp/7188e9b8b52cfc8829608c7cc163713e455f6291/pages/signin/qrcode.js#sha256=18ae399f81182bc9de916e9c77b195df20cc58d6f2d55a62b085a299f1bf1780
// @icon         https://strawwaffle.github.io/glorp/userscript/glorp-icon.png
// @homepageURL  https://github.com/StrawWaffle/glorp/tree/main/pages/userscript
// @downloadURL  https://strawwaffle.github.io/glorp/userscript/glorp.user.js
// @updateURL    https://strawwaffle.github.io/glorp/userscript/glorp.user.js
// @run-at       document-idle
// ==/UserScript==

// What Glorp the app does that destiny.gg's chat doesn't, in the site's chat:
// a Glorp button among the chat's own (next to the eye) opens its settings.
// Hidden phrases, hidden flairs and alert words, which Glorp took from the
// d.gg utilities userscript, are left to that script; they still sync between
// Glorp installs, and this script keeps them as they come.
(function () {
  "use strict";

  var site = location.hostname.replace(/^www\./, "");
  var origin = location.origin;
  var ICON = "data:image/png;base64,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";
  var DEFAULT_SERVER = "https://glorp-sync.netlify.app";

  // ---- Stored state -------------------------------------------------------
  // settings: what syncs, each as { v: value, t: when it was last changed }.
  // local: this browser only (the sync code and server).
  var state = { settings: {}, local: {} };

  function load() {
    return GM.getValue("glorp", null).then(function (saved) {
      if (saved && typeof saved === "object") state = { settings: saved.settings || {}, local: saved.local || {} };
    });
  }
  function save() { return GM.setValue("glorp", state); }

  function get(key, fallback) {
    var s = state.settings[key];
    return s ? s.v : fallback;
  }
  function set(key, value) {
    state.settings[key] = { v: value, t: Date.now() };
    save();
    apply();
    syncSoon();
  }

  // ---- Sync ---------------------------------------------------------------
  // A sync code is 16 random bytes (glorp-sync:1:<hex>). The server sees an ID
  // and AES-GCM ciphertext made from it, never the code (see sync/README.md).
  function hexToBytes(hex) { var b = new Uint8Array(hex.length / 2); for (var i = 0; i < b.length; i++) b[i] = parseInt(hex.substr(i * 2, 2), 16); return b; }
  function bytesToHex(b) { return Array.prototype.map.call(b, function (x) { return ("0" + x.toString(16)).slice(-2); }).join(""); }
  function concat(a, b) { var out = new Uint8Array(a.length + b.length); out.set(a); out.set(b, a.length); return out; }
  function b64(bytes) { var s = ""; for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]); return btoa(s); }
  function unb64(text) { var s = atob(text); var b = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) b[i] = s.charCodeAt(i); return b; }
  var enc = new TextEncoder();

  function readCode(text) {
    var m = /^glorp-sync:1:([0-9a-f]{32})$/i.exec((text || "").trim());
    return m ? m[1].toLowerCase() : null;
  }
  function derive(codeHex) {
    var secret = hexToBytes(codeHex);
    return Promise.all([
      crypto.subtle.digest("SHA-256", concat(enc.encode("glorp-sync-id:"), secret)),
      crypto.subtle.digest("SHA-256", concat(enc.encode("glorp-sync-key:"), secret)),
    ]).then(function (d) {
      return crypto.subtle.importKey("raw", d[1], "AES-GCM", false, ["encrypt", "decrypt"]).then(function (key) {
        return { id: bytesToHex(new Uint8Array(d[0])).slice(0, 32), key: key };
      });
    });
  }
  function request(method, url, body) {
    return new Promise(function (resolve, reject) {
      GM.xmlHttpRequest({
        method: method, url: url, data: body, headers: body ? { "content-type": "application/json" } : {},
        onload: function (r) { resolve({ status: r.status, text: r.responseText }); },
        onerror: function () { reject(new Error("network")); },
        ontimeout: function () { reject(new Error("timeout")); },
        timeout: 15000,
      });
    });
  }
  /** Each setting the newer of the two. */
  function merge(mine, theirs) {
    var out = {};
    Object.keys(mine).forEach(function (k) { out[k] = mine[k]; });
    Object.keys(theirs || {}).forEach(function (k) { if (!out[k] || (theirs[k].t || 0) > (out[k].t || 0)) out[k] = theirs[k]; });
    return out;
  }
  var syncTimer = null;
  var lastSync = null;
  function syncSoon() {
    if (!state.local.code) return;
    clearTimeout(syncTimer);
    syncTimer = setTimeout(syncNow, 1500);
  }
  function syncNow() {
    var code = state.local.code;
    if (!code) return Promise.resolve();
    var url = (state.local.server || DEFAULT_SERVER) + "/api/sync/";
    return derive(code).then(function (k) {
      return request("GET", url + k.id).then(function (r) {
        if (r.status === 404) return {};
        if (r.status !== 200) throw new Error("server " + r.status);
        var blob = unb64(JSON.parse(r.text).data);
        return crypto.subtle.decrypt({ name: "AES-GCM", iv: blob.slice(0, 12) }, k.key, blob.slice(12)).then(function (plain) {
          var doc = JSON.parse(new TextDecoder().decode(plain));
          return doc && doc.s ? doc.s : {};
        });
      }).then(function (theirs) {
        state.settings = merge(state.settings, theirs);
        save();
        apply();
        var iv = crypto.getRandomValues(new Uint8Array(12));
        var plain = enc.encode(JSON.stringify({ v: 1, s: state.settings }));
        return crypto.subtle.encrypt({ name: "AES-GCM", iv: iv }, k.key, plain).then(function (ct) {
          return request("PUT", url + k.id, JSON.stringify({ data: b64(concat(iv, new Uint8Array(ct))) }));
        });
      }).then(function (r) {
        if (r.status !== 200) throw new Error("server " + r.status);
        lastSync = { ok: true, at: Date.now() };
        renderSync();
      });
    }).catch(function (e) {
      lastSync = { ok: false, at: Date.now(), error: String(e.message || e) };
      renderSync();
    });
  }

  // ---- Themes ---------------------------------------------------------------
  // The app's colours by role (Theme.kt). The site's stylesheet uses a handful
  // of colours for each role; every rule using one gets the theme's colour instead.
  var THEMES = {
    Glorp: { bg: "#111113", surface: "#0c1d40", raised: "#15326c", action: "#0090ff", text: "#edeef0", dim: "#9ea3ad", green: "#6ca528", mention: "rgba(243,177,34,0.14)", tagged: "#232323", faint: "#5c6068" },
    AerithLight: { light: true, bg: "#faf8e8", surface: "#eae7d2", raised: "#dcd6b8", action: "#308fd6", text: "#5a4a38", dim: "#918168", green: "#55a637", mention: "#e4f6f0", tagged: "#efe9d2", faint: "#b3a78e" },
    AerithDark: { bg: "#2b3444", surface: "#1f2a3c", raised: "#3b444e", action: "#7bc3e9", text: "#f1ecda", dim: "#b6ae98", green: "#7dcf9b", mention: "#27463f", tagged: "#243040", faint: "#717888" },
  };
  var THEME_LABELS = [["", "The site's own"], ["Glorp", "Glorp"], ["AerithLight", "Aerith light"], ["AerithDark", "Aerith dark"]];
  // chat-gui's colours (abstracts/_vars.scss) and the role each plays.
  var ROLES = {
    "rgb(8, 8, 8)": "bg", "rgb(3, 3, 3)": "bg", "rgb(17, 17, 17)": "bg", "rgb(0, 0, 0)": "bg",
    "rgb(21, 21, 21)": "surface", "rgb(34, 34, 34)": "surface", "rgb(51, 51, 51)": "raised", "rgb(35, 35, 35)": "tagged",
    "rgb(222, 222, 222)": "text", "rgb(185, 185, 185)": "text", "rgb(255, 247, 249)": "text", "rgb(220, 220, 220)": "text",
    "rgb(153, 153, 153)": "dim", "rgb(131, 131, 131)": "dim", "rgb(102, 102, 102)": "dim",
    "rgb(6, 38, 62)": "mention", "rgb(2, 194, 255)": "action", "rgb(74, 142, 204)": "action", "rgb(79, 177, 244)": "action",
    "rgb(108, 165, 40)": "green", "rgb(64, 64, 64)": "faint", "rgb(65, 65, 65)": "faint",
  };
  var COLOUR_PROPS = ["color", "background-color", "border-color", "border-top-color", "border-bottom-color", "border-left-color", "border-right-color", "outline-color", "fill"];
  var themeRules = null; // [selector, prop, role][], read once from the site's stylesheets

  function readThemeRules() {
    if (themeRules) return Promise.resolve(themeRules);
    var out = [];
    function walk(rules) {
      Array.prototype.forEach.call(rules, function (r) {
        if (r.cssRules && !r.selectorText) { walk(r.cssRules); return; }
        if (!r.style || !r.selectorText) return;
        COLOUR_PROPS.forEach(function (p) {
          var v = r.style.getPropertyValue(p);
          var role = v && ROLES[v.trim()];
          if (role) out.push([r.selectorText, p, role]);
        });
      });
    }
    var pending = [];
    Array.prototype.forEach.call(document.styleSheets, function (sheet) {
      try { walk(sheet.cssRules); } catch (e) {
        // A stylesheet from another origin: read again through a sheet of our own.
        if (sheet.href) pending.push(request("GET", sheet.href).then(function (r) {
          var own = new CSSStyleSheet(); own.replaceSync(r.text); walk(own.cssRules);
        }).catch(function () {}));
      }
    });
    return Promise.all(pending).then(function () { themeRules = out; return out; });
  }

  var themeStyle = document.createElement("style");
  function applyTheme() {
    // The site's own look unless a theme was picked in this browser: the app's choice
    // syncs, but only shows here once you've chosen a Glorp theme here too.
    var name = state.local.siteTheme === false ? get("theme", "") : "";
    var t = THEMES[name];
    if (!t) { themeStyle.textContent = ""; return; }
    readThemeRules().then(function (rules) {
      var css = rules.map(function (r) { return r[0] + "{" + r[1] + ":" + t[r[2]] + " !important}"; }).join("\n");
      css += "\nbody,#chat,.chat-lines{background-color:" + t.bg + " !important}" +
        "\n.msg-chat a.externallink{color:" + t.action + " !important}";
      // The site's icons are light shapes for a dark page: on a light theme, darkened.
      if (t.light) css += "\n.btn-icon,.chat-menu-close{filter:invert(1) brightness(.45)}";
      themeStyle.textContent = css;
    });
  }

  // ---- Name icons -------------------------------------------------------------
  function icons() { return get("nameIcons", {}) || {}; }
  function decorate(msg) {
    var user = msg.querySelector(":scope > .user");
    if (!user) return;
    var old = msg.querySelector(":scope > .glorp-icon");
    var icon = icons()[(user.textContent || "").trim().toLowerCase()];
    if (!icon) { if (old) old.remove(); return; }
    if (old) { old.textContent = icon; return; }
    var span = document.createElement("span");
    span.className = "glorp-icon";
    span.textContent = icon;
    user.parentNode.insertBefore(span, user);
  }
  function decorateAll() { document.querySelectorAll(".msg-chat.msg-user").forEach(decorate); }

  // ---- Other streams and sticky mentions -----------------------------------------
  var extraStyle = document.createElement("style");
  function applyExtras() {
    var css = ".glorp-icon{margin-left:.3em;margin-right:-.15em}\n";
    // Hide, rather than dim, what the eye button dims: messages from people watching another stream.
    if (get("otherStreams", "Show") === "Hide") {
      css += ".chat.watching-focus .msg-chat.msg-user:not(.watching-same):not(.msg-own):not(.msg-highlight){display:none!important}\n";
    }
    extraStyle.textContent = css;
  }

  var stuck = null;
  var latestMention = null;
  var closedMention = null;
  function checkSticky() {
    if (!stuck) return;
    var frame = document.getElementById("chat-output-frame");
    var on = get("stickyMentions", true) !== false;
    var m = latestMention;
    if (!on || !m || !m.isConnected || m === closedMention) { stuck.hidden = true; return; }
    var top = frame.getBoundingClientRect().top;
    var scrolledPast = m.getBoundingClientRect().bottom < top;
    if (!scrolledPast) { stuck.hidden = true; return; }
    if (stuck._for !== m) {
      stuck._for = m;
      stuck.querySelector(".glorp-stuck-line").innerHTML = "";
      var copy = m.cloneNode(true);
      copy.removeAttribute("id");
      stuck.querySelector(".glorp-stuck-line").appendChild(copy);
    }
    stuck.hidden = false;
  }

  // ---- Applying everything ---------------------------------------------------------
  function apply() {
    applyTheme();
    applyExtras();
    decorateAll();
    checkSticky();
    renderSettings();
  }

  // ---- The panel ----------------------------------------------------------------------
  var panel = null;
  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === "text") e.textContent = attrs[k];
      else if (k.indexOf("on") === 0) e.addEventListener(k.slice(2), attrs[k]);
      else e.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) e.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return e;
  }

  function buildPanel() {
    panel = el("div", { id: "glorp-settings", "class": "chat-menu right" }, [
      el("div", { "class": "chat-menu-inner" }, [
        el("div", { "class": "toolbar" }, [el("h5", {}, [el("span", { text: "Glorp" }), el("i", { "class": "chat-menu-close", onclick: function () { panel.classList.remove("active"); } })])]),
        el("div", { "class": "scrollable", style: "overflow-y:auto" }, [el("div", { "class": "content" }, [el("div", { id: "glorp-settings-form" })])]),
      ]),
    ]);
    document.getElementById("chat").appendChild(panel);
    renderSettings();
  }

  function renderSettings() {
    var form = panel && panel.querySelector("#glorp-settings-form");
    if (!form) return;
    form.textContent = "";

    form.appendChild(el("h4", { text: "Look" }));
    var select = el("select", { "class": "form-control", id: "glorp-theme", onchange: function () {
      if (select.value) { state.local.siteTheme = false; set("theme", select.value); }
      else { state.local.siteTheme = true; save(); apply(); }
    } });
    var shown = state.local.siteTheme === false ? get("theme", "") : "";
    THEME_LABELS.forEach(function (o) { var opt = el("option", { value: o[0], text: o[1] }); if (o[0] === shown) opt.selected = true; select.appendChild(opt); });
    form.appendChild(el("div", { "class": "form-group" }, [el("label", { "for": "glorp-theme", text: "Theme" }), select]));

    form.appendChild(el("h4", { text: "Chat" }));
    var hide = el("input", { type: "checkbox", onchange: function () { set("otherStreams", hide.checked ? "Hide" : "Dim"); } });
    hide.checked = get("otherStreams", "Show") === "Hide";
    form.appendChild(el("div", { "class": "form-group checkbox" }, [el("label", { title: "With the eye button on, messages from people watching another stream are hidden instead of dimmed" }, [hide, " Hide other streams' chat, not just dim it"])]));
    var sticky = el("input", { type: "checkbox", onchange: function () { set("stickyMentions", sticky.checked); } });
    sticky.checked = get("stickyMentions", true) !== false;
    form.appendChild(el("div", { "class": "form-group checkbox" }, [el("label", { title: "Your newest mention stays at the top of chat after it scrolls past, until you close it" }, [sticky, " Keep my latest mention in view"])]));

    form.appendChild(el("h4", { text: "Name icons" }));
    form.appendChild(el("p", { "class": "glorp-note", text: "An emoji before someone's name, that only you see." }));
    var list = icons();
    Object.keys(list).sort().forEach(function (nick) {
      form.appendChild(el("div", { "class": "form-group glorp-row" }, [
        el("span", { text: list[nick] + " " + nick }),
        el("a", { href: "#", text: "Remove", onclick: function (e) { e.preventDefault(); var next = Object.assign({}, icons()); delete next[nick]; set("nameIcons", next); } }),
      ]));
    });
    var nickIn = el("input", { "class": "form-control", placeholder: "Name", spellcheck: "false" });
    var iconIn = el("input", { "class": "form-control", placeholder: "Emoji", maxlength: "12" });
    form.appendChild(el("div", { "class": "form-group glorp-add" }, [nickIn, iconIn, el("button", { "class": "btn btn-dark", text: "Add", onclick: function () {
      var nick = nickIn.value.trim().toLowerCase(); var icon = iconIn.value.trim();
      if (!/^\w{3,20}$/.test(nick) || !icon) return;
      var next = Object.assign({}, icons()); next[nick] = icon; set("nameIcons", next);
    } })]));

    form.appendChild(el("h4", { text: "Sync with Glorp" }));
    form.appendChild(el("div", { id: "glorp-sync" }));
    renderSync();

    form.appendChild(el("h4", { text: "Glorp app" }));
    form.appendChild(el("div", { "class": "form-group" }, [el("button", { "class": "btn btn-dark", text: "Sign in to Glorp", onclick: openSignIn })]));
  }

  function renderSync() {
    var box = panel && panel.querySelector("#glorp-sync");
    if (!box) return;
    box.textContent = "";
    var code = state.local.code;
    if (!code) {
      box.appendChild(el("p", { "class": "glorp-note", text: "Keeps these settings, and Glorp's own, the same here and in the app." }));
      box.appendChild(el("button", { "class": "btn btn-dark", text: "Start syncing", onclick: function () {
        state.local.code = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
        save(); syncNow(); renderSync();
      } }));
      var codeIn = el("input", { "class": "form-control", placeholder: "Or paste a sync code from the app", spellcheck: "false" });
      box.appendChild(el("div", { "class": "form-group" }, [codeIn, el("button", { "class": "btn btn-dark", text: "Join", onclick: function () {
        var c = readCode(codeIn.value);
        if (!c) { codeIn.value = ""; codeIn.placeholder = "That isn't a Glorp sync code"; return; }
        state.local.code = c; save(); syncNow(); renderSync();
      } })]));
      return;
    }
    var text = "glorp-sync:1:" + code;
    var status = lastSync ? (lastSync.ok ? "Synced at " + new Date(lastSync.at).toLocaleTimeString() + "." : "Couldn't sync (" + lastSync.error + ").") : "Syncing…";
    box.appendChild(el("p", { "class": "glorp-note", text: status }));
    box.appendChild(el("p", { "class": "glorp-note", text: "In Glorp, Settings → Sync → Join, then scan this. Anyone with the code can see and change your Glorp settings." }));
    var qrBox = el("div", { "class": "glorp-qr", hidden: "" });
    var show = el("button", { "class": "btn btn-dark", text: "Show the code", onclick: function () {
      if (!qrBox.hidden) { qrBox.hidden = true; qrBox.textContent = ""; show.textContent = "Show the code"; return; }
      var q = qrcode(0, "M"); q.addData(text); q.make();
      qrBox.innerHTML = q.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
      qrBox.appendChild(el("code", { text: text }));
      qrBox.hidden = false; show.textContent = "Hide the code";
    } });
    box.appendChild(el("div", { "class": "form-group" }, [show, el("button", { "class": "btn btn-dark", text: "Sync now", onclick: syncNow }),
      el("button", { "class": "btn btn-dark", text: "Stop syncing here", onclick: function () { state.local.code = null; lastSync = null; save(); renderSync(); } })]));
    box.appendChild(qrBox);
  }

  // ---- Sign in to Glorp (reads the login cookies, shows the code Glorp scans) ----------
  function readCookie(name) {
    return new Promise(function (resolve) {
      var details = { url: origin, name: name };
      try {
        if (typeof GM !== "undefined" && GM.cookie && GM.cookie.list) {
          GM.cookie.list(details).then(function (list) { resolve(pick(list)); }, function () { resolve(null); });
        } else if (typeof GM_cookie !== "undefined" && GM_cookie.list) {
          GM_cookie.list(details, function (list, error) { resolve(error ? null : pick(list)); });
        } else {
          resolve(null);
        }
      } catch (e) { resolve(null); }
    });
    function pick(list) {
      var found = (list || []).filter(function (c) { return c.name === name && c.value; })[0];
      return found ? found.value : null;
    }
  }

  var signInHost = null;
  function closeSignIn() { if (signInHost) signInHost.remove(); signInHost = null; }
  function openSignIn() {
    closeSignIn();
    // The sign-in box opens on the top page, where there's room, if it can.
    signInHost = document.createElement("div");
    var root = signInHost.attachShadow({ mode: "closed" });
    root.innerHTML =
      "<style>" +
      ".back{position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:2147483647;display:flex;align-items:center;justify-content:center;font:14px/1.45 system-ui,sans-serif}" +
      ".box{background:#18191b;color:#edeef0;border:1px solid #363a3f;border-radius:10px;padding:20px;width:min(420px,calc(100vw - 32px));max-height:calc(100vh - 32px);overflow:auto}" +
      "h2{margin:0 0 8px;font-size:17px}p{margin:8px 0}.quiet{color:#9a9ea6}.problem{color:#ff8f8f}ol{margin:8px 0;padding-left:20px}" +
      "button{font:inherit;padding:8px 14px;border-radius:8px;border:1px solid #43484e;background:#212225;color:#edeef0;cursor:pointer;margin:8px 8px 0 0}" +
      "button.primary{background:#0090ff;border-color:#0090ff;color:#fff}button:disabled{opacity:.5;cursor:default}" +
      ".qr{background:#fff;border-radius:8px;padding:8px;margin:12px auto;width:240px}.qr svg{display:block;width:100%;height:auto}" +
      "label{display:flex;gap:8px;align-items:flex-start;margin:12px 0}a{color:#70b8ff}" +
      "</style>" +
      "<div class='back'><div class='box' role='dialog' aria-label='Sign in to Glorp'>" +
      "<h2>Sign in to Glorp</h2>" +
      "<div class='body'><p class='quiet'>Reading this site's login cookies…</p></div>" +
      "<button class='close'>Close</button>" +
      "</div></div>";
    root.querySelector(".close").addEventListener("click", closeSignIn);
    root.querySelector(".back").addEventListener("click", function (e) { if (e.target === e.currentTarget) closeSignIn(); });
    document.body.appendChild(signInHost);
    var body = root.querySelector(".body");

    Promise.all([readCookie("sid"), readCookie("rememberme")]).then(function (values) {
      var sid = values[0];
      var rememberme = values[1] || "";
      if (!sid) {
        body.innerHTML =
          "<p class='problem'>Your userscript manager didn't give this script the login cookies.</p>" +
          "<p>In Violentmonkey, tick <b>Allow GM_cookie to access HTTP-only cookies</b> in two places, then reload this page " +
          "(as named in Violentmonkey 2.50; other versions may word or place them differently):</p>" +
          "<ol><li>Violentmonkey's dashboard, <b>Settings</b>.</li>" +
          "<li>This script's own <b>Settings</b> tab: in the dashboard, open <b>Glorp</b> with its edit button.</li></ol>" +
          "<p>In Tampermonkey, only the beta can read them. Or you may not be signed in here: sign in with Remember me ticked.</p>" +
          "<p class='quiet'>Without a manager that can, <a href='https://strawwaffle.github.io/glorp/signin/?site=" + encodeURIComponent(site) + "'>Glorp's sign-in page</a> shows how to copy them by hand.</p>";
        return;
      }
      body.innerHTML =
        (rememberme ? "" : "<p class='quiet'>There's no rememberme cookie, so Glorp gets signed out again when this browser session ends. Sign in with Remember me ticked to stay signed in.</p>") +
        "<p>The code holds your destiny.gg login. Anyone who scans or copies it is signed in as you until you sign out on the site.</p>" +
        "<label><input type='checkbox' class='ack'> <span>Nobody else can see this screen.</span></label>" +
        "<button class='primary reveal' disabled>Show the code</button>";
      var ack = body.querySelector(".ack");
      var reveal = body.querySelector(".reveal");
      ack.addEventListener("change", function () { reveal.disabled = !ack.checked; });
      reveal.addEventListener("click", function () {
        if (!ack.checked) return;
        var code = "glorp-signin:1:" + site + ":" + sid + ":" + rememberme;
        var q = qrcode(0, "M");
        q.addData(code);
        q.make();
        body.innerHTML =
          "<p>In Glorp, open the menu, choose <b>Sign in</b>, then <b>Scan the code</b>, and point the camera here.</p>" +
          "<div class='qr'>" + q.createSvgTag({ cellSize: 6, margin: 2, scalable: true }) + "</div>" +
          "<button class='copy'>Copy it as text</button>";
        var copy = body.querySelector(".copy");
        copy.addEventListener("click", function () {
          navigator.clipboard.writeText(code).then(function () { copy.textContent = "Copied"; });
        });
      });
    });
  }
  // Leaving the tab hides the sign-in code.
  document.addEventListener("visibilitychange", function () { if (document.hidden) closeSignIn(); });

  // ---- Into the chat --------------------------------------------------------------------
  function mountChat() {
    var groups = document.querySelectorAll("#chat-tools-wrap .chat-tools-group");
    if (groups.length < 2 || document.getElementById("chat-glorp-btn")) return false;
    document.head.appendChild(themeStyle);
    document.head.appendChild(extraStyle);
    var style = document.createElement("style");
    style.textContent =
      "#chat-glorp-btn .btn-icon{background:url(" + ICON + ") center/contain no-repeat}" +
      // As the site lays out its own settings menu (menus/_settings.scss).
      "#glorp-settings-form{margin:.9em 0}" +
      "#glorp-settings .form-group{margin:.9em;display:block;position:relative}" +
      "#glorp-settings h4{font-size:.9em;margin-top:1.8em;margin-bottom:.9em;padding-left:.9em;color:#494949;text-transform:uppercase;font-weight:600}" +
      "#glorp-settings label{display:inline-block;font-weight:normal;max-width:100%;margin-bottom:.6em}" +
      "#glorp-settings .checkbox label{margin-bottom:0;cursor:pointer;display:flex;align-items:center}" +
      "#glorp-settings .checkbox input{margin:0 .3em 0 0}" +
      "#glorp-settings select,#glorp-settings input.form-control{border-radius:.25em;padding:.3em;width:100%;box-sizing:border-box}" +
      "#glorp-settings .btn{background:#222;color:#dedede;border:1px solid #333;border-radius:.25em;padding:.35em .8em;cursor:pointer}" +
      "#glorp-settings .btn:hover{background:#333}" +
      "#glorp-settings .glorp-note{color:#999;font-size:.9em;margin:.3em .9em}" +
      "#glorp-settings .glorp-row{display:flex;justify-content:space-between;gap:1em}" +
      "#glorp-settings .glorp-add{display:flex;gap:.4em}#glorp-settings .glorp-add input{min-width:0;flex:1}#glorp-settings .glorp-add input+input{flex:0 0 5em}" +
      "#glorp-settings .btn{margin:.2em .4em .2em 0}" +
      "#glorp-settings .glorp-qr{background:#fff;padding:6px;border-radius:6px;max-width:220px}#glorp-settings .glorp-qr svg{display:block;width:100%;height:auto}" +
      "#glorp-settings .glorp-qr code{display:block;color:#111;font-size:10px;word-break:break-all;margin-top:4px}" +
      "#glorp-stuck{position:absolute;top:0;left:0;right:0;z-index:5;background:inherit;border-bottom:1px solid #333;display:flex;align-items:flex-start}" +
      "#glorp-stuck .glorp-stuck-line{flex:1;min-width:0}#glorp-stuck .glorp-stuck-close{cursor:pointer;padding:.3em .6em;opacity:.6}";
    document.head.appendChild(style);

    var button = el("a", { id: "chat-glorp-btn", "class": "chat-tool-btn", title: "Glorp", "aria-label": "Glorp" }, [el("i", { "class": "btn-icon" })]);
    groups[1].insertBefore(button, groups[1].firstChild);
    buildPanel();
    button.addEventListener("click", function (e) {
      e.stopPropagation();
      // As the site's own menus: one open at a time.
      document.querySelectorAll("#chat .chat-menu.active").forEach(function (m) { if (m !== panel) m.classList.remove("active"); });
      panel.classList.toggle("active");
      button.classList.toggle("active", panel.classList.contains("active"));
      if (panel.classList.contains("active")) syncSoon();
    });
    ["chat-emoticon-btn", "chat-whisper-btn", "chat-settings-btn", "chat-users-btn"].forEach(function (id) {
      var b = document.getElementById(id);
      if (b) b.addEventListener("click", function () { panel.classList.remove("active"); button.classList.remove("active"); });
    });
    var output = document.getElementById("chat-output-frame");
    if (output) output.addEventListener("click", function () { panel.classList.remove("active"); button.classList.remove("active"); });

    // The sticky mention bar, over the top of chat.
    stuck = el("div", { id: "glorp-stuck", hidden: "" }, [
      el("div", { "class": "glorp-stuck-line" }),
      el("span", { "class": "glorp-stuck-close", title: "Close", text: "✕", onclick: function () { closedMention = latestMention; stuck.hidden = true; } }),
    ]);
    if (output) { output.style.position = output.style.position || "relative"; output.appendChild(stuck); }

    // New lines: name icons, and mentions of you to keep in view.
    var seen = new MutationObserver(function (records) {
      records.forEach(function (r) {
        r.addedNodes.forEach(function (n) {
          if (n.nodeType !== 1 || !n.classList.contains("msg-chat")) return;
          if (n.classList.contains("msg-user")) decorate(n);
          if (n.classList.contains("msg-highlight") && n.classList.contains("msg-user")) latestMention = n;
        });
      });
      checkSticky();
    });
    var watchLines = function () {
      var lines = document.querySelector("#chat-output-frame .chat-lines");
      if (!lines) return setTimeout(watchLines, 500);
      seen.observe(lines, { childList: true });
      lines.addEventListener("scroll", checkSticky, { passive: true });
      decorateAll();
    };
    watchLines();
    apply();
    syncSoon();
    return true;
  }

  load().then(function () {
    if (typeof GM_registerMenuCommand !== "undefined") GM_registerMenuCommand("Sign in to Glorp", openSignIn);
    // A link where the site lists your account.
    if (window.top === window && /^\/profile/.test(location.pathname)) {
      var link = el("a", { href: "#", text: "Sign in to Glorp", style: "display:inline-block;margin:12px 0;color:#70b8ff", onclick: function (e) { e.preventDefault(); openSignIn(); } });
      var main = document.querySelector("main") || document.body;
      main.insertBefore(link, main.firstChild);
    }
    // The chat (on its own page, or in bigscreen's frame) is ready a little after load.
    var tries = 0;
    (function tryMount() {
      if (mountChat() || ++tries > 60) return;
      setTimeout(tryMount, 500);
    })();
  });
})();
