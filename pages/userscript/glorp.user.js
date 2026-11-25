// ==UserScript==
// @name         Glorp
// @namespace    https://strawwaffle.github.io/glorp/
// @version      2.8
// @description  Glorp's own chat features in destiny.gg's chat on a computer: themes, name icons, hiding other streams' chat, sticky mentions, settings synced with the Glorp app, and signing Glorp in without the developer tools.
// @match        https://www.destiny.gg/*
// @match        https://www.omniliberal.dev/*
// @grant        GM_cookie
// @grant        GM.cookie
// @grant        GM.getValue
// @grant        GM.setValue
// @grant        GM.xmlHttpRequest
// @grant        GM_registerMenuCommand
// @grant        GM_addValueChangeListener
// @connect      netlify.app
// @connect      strawwaffle.dev
// @connect      polecat.me
// @connect      rustlesearch.dev
// @connect      cdn.destiny.gg
// @connect      cdn.omniliberal.dev
// @require      https://raw.githubusercontent.com/StrawWaffle/glorp/7188e9b8b52cfc8829608c7cc163713e455f6291/pages/signin/qrcode.js#sha256=18ae399f81182bc9de916e9c77b195df20cc58d6f2d55a62b085a299f1bf1780
// @icon         https://strawwaffle.dev/glorp/userscript/glorp-icon.png
// @homepageURL  https://github.com/StrawWaffle/glorp/tree/main/pages/userscript
// @downloadURL  https://strawwaffle.dev/glorp/userscript/glorp.user.js
// @updateURL    https://strawwaffle.dev/glorp/userscript/glorp.user.js
// @run-at       document-idle
// ==/UserScript==
// The namespace keeps the old github.io address on purpose: script managers tell
// scripts apart by namespace and name, so changing it would install a second copy.

// What Glorp the app does that destiny.gg's chat doesn't, in the site's chat:
// a Glorp button among the chat's own (next to the eye) opens its settings.
// Hidden phrases, hidden flairs and alert words, which Glorp took from the
// d.gg utilities userscript, are left to that script; they still sync between
// Glorp installs, and this script keeps them as they come.
(function () {
  "use strict";

  var site = location.hostname.replace(/^www\./, "");
  var origin = location.origin;
  var ICON = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAuCAYAAABu3ppsAAAG+UlEQVR42uWaW4xdZRXH/2ufM1NahqG0tAak0SJQaCBgjVbB2wsqpF5eCgQbfDAgJMqLKbxoDGoID16IaaIkig8MmjQxGhs0YuUBL8glQCgXEyvXdqS0ZbAtnXbmnP3zwf+XfGz2nLPPOVNj4k6+7Nl7f99a31rfuvzXOiP9P15A/K/QjSEYtCR1vbaQ1I0IRtx4y3+WpklElIutnWKxT6TXul78Bj4BoIiIEjhb0vWSzpE0L+kRSXdFxCwQg5xEPh/YIulTkk6W9KKkeyLi8cR3UTQPXArs4e3XH4DJQU8CKIBx4Kc1NN8ENo/sb0B4rAZ2m/jTwE3A7cBhv/ux57UGsXngq17fBX4EfAn4s98dAs4dxJx6MdpsogeAtdn3rWb+CrAmCdxQ+0uBR4ES2JZ9Wwk8Z35b/a69EK2ioY+c7ggxHREvABMW7remUUhaYpuOBrZfet6kX//OQk1GxEFJu83vzLRsWAHSwn967jrgQxFxJCK6kq4woyOSZqz9no6cOfpx05WkTRFRRsQh4CxJ681v76IkF2AJ8IiPdRr4LjBl8wG4sxLPm5rmjZnj7gC+B7zk5yPAeSP5QB4FgIsy20xXacanNLX/TCkFMGbn7VTo7gc2jbz5GiFWArvMsAPsBc4YhlFGswX8yafZAV4E1jel2SzbReCkclDSHsOJlqTlktZ4MzGkYlbYWRNMeV3SSzYzFkUAC1Ga4WG/6khaJumCYXDVf0gGktZ6pIzbNRbqNiFSDJH6j/rVMd83+n1pu255FB6R2XwLaFu7SeCN1vTxLDrNNd1XMQRy/Zfvuy3E54DT0ilFRNej9MAjfetYu9jGrzHt50x3NiI6TQNCewgBXvf9jwZ075d0ZUTc67B3gU1iuaRTbWbHJc047k87vj8haYOki22WD0l6n0+ysTm2BwB0Y0Ap6c3sJH5iAe4AvijpImftftespF3OxCdLulfSY/72hoPGWPKHkQQAWj7yY35eZYdbagGuk3SppLPM8HFJz0s66NHxJiclrZS0RtL5kj5gFgckfdsnIUkTwFhEzOdQfmgBIqLr9P5pb/a99p1p2+oWSTdJelnSTkl7IuJID4VMOHRukHSJpF9GxN+Ad3nKJyQ9CeyQNBURTyeTalxv5FkVuNloM79+AZzWp6JKEaedRZ5ec1vAHRU+bwC3DFz5ZUXMtzJiT7kOeHeNsG8Lm33qi7eE28qcdcAXgPsy3j+wEopBNn8eMO8U//0UKk90Z6LC45YMMH62rjbo5QOrsu/PRMQMsCQlmVE7EX3gxZhD9D+yXHVG30SWig1LeatDWClp3oQ7KTGdKK2bdsePM1mGvhY4KSI6TXD65Zn9fa0OGVactDUMGq2hETWmfB0w573cWjWjhUxoXVaRnWLCRQa4VAe2zLTvCWWxvRdgS8FgWWp2OVG+pcRsL1BCPmjIsELSGmfGMgE6YKmkKyVdKGnCEHtnRDwzQI/pdEkfdVIL2/vOiDiQx31go2H2vKSppqXeFcBx92c+ksfqLDdUr2PAbe71FHVRKjOLq4CXa2jck3Ut0tzz3Q0pgRt6lq5Z8rrbBH+VEYzsfglw0HPmsnALcPtCPuP7x62YtHYuE+DzlblJiG3+/vvkKz3DOPCYF3zFk9s1WtwIPFijxaOGHvncJHy7Zk3p/tDmGhSQEuTHrKDngXfktNvVgsVYZZX94VCy/2plFhEPA5dL+rBh8ApJ464TZswg0j3rPn9H0l9t0/uNSh+KiKN1/VXz6zi0rjYo3NdL+2OWFGOg9QuYRLHICazVo5HwpPczC7yn9gSyU5gH7pL0DUPktZKerda8WY1cV9AjaZMRZ0qGz0raURM6WaAGTk2y5VndPSVpz4INtGSr/vsvtru73ceMAVolE8CrNf6xoenpZcDvNvvJXmC8Lw7LvDzvmn2yaectU8CXMyfFbfSlTRpgGY1zHRTy7l/Rt6i3I836cZ+kF/o1WSumVUTENklbXQdPSboxImYbAkEs5GtZqXm4rxVUTOjXlvzrozgtcOYwEDyFbtcGuHsXfUtKa7CVhdK/p7lAKrKbotEiIqYzZ6eBEJF16Tquq+VaekzSXM+fs7Js+3NLfj+wYtRfeYZcuwzY7n38pu4UowfYukzSA05OuyT9TNKj7jjsdyKKVC9k9NrZeySVyS8qPldmVpBC7ZhPfq2R5xZJH/S8qyNie7VLEX0Q42ck3WmCypz6QLY+tVzwBhL0nfe30mPc3yP7LptKEmBZ1uxN16uSvhkRP6xrsUQD2Lta0lWSLjNkONtMT+S1V9JTku6XtD35UZ3dR8PCIz1P2pneKekkO1lpgcat/Vam+fzqVk5lide0XGcnU9wn6RVJM4n3SL8XV9Go/rv/k9HqFwD+DYPx3wQbEJJ3AAAAAElFTkSuQmCC";
  var DEFAULT_SERVER = "https://glorp-sync.netlify.app";
  var SCRIPT_URL = "https://strawwaffle.dev/glorp/userscript/glorp.user.js";

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
    // Glorp's own, from its homepage: near-black green and lime, or pale green.
    Glorp: { bg: "#0a0f08", surface: "#131b10", raised: "#1b2716", action: "#b7fa44", text: "#eef6e4", dim: "#9eb38c", green: "#8bd450", mention: "#1f2b0f", tagged: "#1b2716", faint: "#5d7249", mentionBar: "#b7fa44", page: "#0a0f08", card: "#131b10", bar: "#131b10", onBar: "#eef6e4", actionBg: "#263a10", actionFill: "#3d6b14" },
    GlorpLight: { light: true, bg: "#fbfef5", surface: "#eef6e2", raised: "#d3e5bb", action: "#3f7d12", text: "#1c2a12", dim: "#5d7249", green: "#4e8f1a", mention: "#eaf7d0", tagged: "#eef6e2", faint: "#9eb38c", mentionBar: "#78b52a", page: "#eef6e2", card: "#fbfef5", bar: "#b7fa44", onBar: "#13240a", actionBg: "#e3f5c4", actionFill: "#3f7d12" },
    // Aerith's: cream and brown or slate, a sky-blue bar, teal for mentions, buttons on round tiles.
    AerithLight: { light: true, bg: "#faf8e8", surface: "#efecd8", raised: "#dcd6b8", action: "#308fd6", text: "#5a4a38", dim: "#918168", green: "#55a637", mention: "#e4f6f0", tagged: "#efe9d2", faint: "#b3a68a", mentionBar: "#6de1c1", tile: "#efecd8", field: "#ffffff", fieldBorder: "#b3a68a", dash: "#dcd6b8", page: "#efe9d2", card: "#faf8e8", bar: "#6fb4ec", onBar: "#ffffff", actionBg: "#e2f0fb", actionFill: "#308fd6" },
    AerithDark: { bg: "#2b3444", surface: "#1f2a3c", raised: "#3b444e", action: "#7bc3e9", text: "#f1ecda", dim: "#b6ae98", green: "#85d28b", mention: "#27463f", tagged: "#243040", faint: "#717888", mentionBar: "#6de1c1", tile: "#243040", field: "#1a2332", fieldBorder: "#6fb4ec", dash: "#3b4658", page: "#1f2a3c", card: "#2b3444", bar: "#3f6f9e", onBar: "#f3eedc", actionBg: "#2c4a66", actionFill: "#3f6f9e" },
  };
  // "Destiny" (destiny.gg) is the site as it is: picking it here syncs it to the app.
  var THEME_LABELS = [["", "The site's own (this browser only)"], ["Glorp", "Glorp dark"], ["GlorpLight", "Glorp light"], ["Destiny", "destiny.gg"], ["AerithLight", "Aerith light"], ["AerithDark", "Aerith dark"]];
  // chat-gui's colours (abstracts/_vars.scss) and the role each plays.
  var ROLES = {
    "rgb(8, 8, 8)": "bg", "rgb(3, 3, 3)": "bg", "rgb(17, 17, 17)": "bg", "rgb(0, 0, 0)": "bg",
    "rgb(21, 21, 21)": "surface", "rgb(34, 34, 34)": "surface", "rgb(51, 51, 51)": "raised", "rgb(35, 35, 35)": "tagged",
    "rgb(222, 222, 222)": "text", "rgb(185, 185, 185)": "text", "rgb(255, 247, 249)": "text", "rgb(220, 220, 220)": "text",
    "rgb(153, 153, 153)": "dim", "rgb(131, 131, 131)": "dim", "rgb(102, 102, 102)": "dim",
    "rgb(6, 38, 62)": "mention", "rgb(2, 194, 255)": "action", "rgb(74, 142, 204)": "action", "rgb(79, 177, 244)": "action",
    "rgb(108, 165, 40)": "green",
    // The rest of the site (web.css): Radix's grey and blue scales.
    "rgb(17, 17, 19)": "page", "rgb(24, 25, 27)": "card", "rgb(33, 34, 37)": "hover",
    "rgb(39, 42, 45)": "raised", "rgb(46, 49, 53)": "raised", "rgb(54, 58, 63)": "raised", "rgb(67, 72, 78)": "faint",
    "rgb(90, 97, 105)": "dim", "rgb(105, 110, 119)": "dim", "rgb(119, 123, 132)": "dim", "rgb(175, 179, 186)": "dim",
    "rgb(237, 238, 240)": "text", "rgb(0, 144, 255)": "action", "rgb(112, 184, 255)": "action", "rgb(59, 158, 255)": "action",
    "rgb(13, 40, 71)": "actionBg", "rgb(0, 51, 98)": "actionBg", "rgb(17, 25, 39)": "actionBg", "rgb(64, 64, 64)": "faint", "rgb(65, 65, 65)": "faint",
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
      // A button filled with the action colour takes the deeper shade, which white text reads on.
      var css = rules.map(function (r) {
        var role = r[2] === "action" && r[1] === "background-color" ? "actionFill" : r[2];
        // A name's colour is its flair's (flairs.css): the theme only recolours names with no flair.
        var selector = r[1] === "color" ? nameSafe(r[0]) : r[0];
        return selector + "{" + r[1] + ":" + colour(t, role) + " !important}";
      }).join("\n");
      // Glorp's own menus scroll as chat does: a thin see-through handle, no track.
      css += "\n#glorp-settings .scrollable,#glorp-mentions .scrollable,#glorp-search .scrollable{scrollbar-width:thin;scrollbar-color:" + (t.light ? "rgba(0,0,0,.3)" : "rgba(255,255,255,.4)") + " transparent}";
      // Bigscreen's page around chat: its header on the theme's bar, chat's own top bar on chat's colour.
      css += "\nheader.header,.navbar{background-color:" + colour(t, "bar") + " !important;color:" + colour(t, "onBar") + " !important;border-radius:0 0 12px 12px}" +
        "\n#chat-controls,#chat-panel-embeds{background-color:" + t.bg + " !important}";
      css += "\n" + (/^\/embed\/chat/.test(location.pathname) ? "body," : "") + "#chat,.chat-lines{background-color:" + t.bg + " !important}" +
        "\n.msg-chat a.externallink{color:" + t.action + " !important}";
      // The site's icons are light shapes for a dark page: on a light theme, darkened.
      if (t.light) css += "\n.btn-icon,.chat-menu-close{filter:invert(1) brightness(.45)}";
      // A bar down the left of lines that mention you, where the theme has one.
      if (t.mentionBar) css += "\n.msg-chat.msg-highlight{box-shadow:inset .3em 0 0 " + t.mentionBar + "}";
      if (t.tile) css += aerithShapes(t);
      themeStyle.textContent = css;
    });
  }

  /**
   * A colour rule's selector kept off flaired names, whose colour is their
   * flair's (flairs.css): `.user` only where the name has no flair, and links
   * (names are links) never names.
   */
  function nameSafe(selector) {
    return selector.split(",").map(function (part) {
      part = part.trim().replace(/\.user$/, '.user[class="user"]');
      return /(^|[\s>+~])a([:.\[][^\s>+~]*)?$/.test(part) ? part + ":not(.user)" : part;
    }).join(",");
  }

  /** A role's colour in theme [t], with the site-only roles falling back on the chat's. */
  function colour(t, role) {
    if (t[role]) return t[role];
    return { page: t.surface, card: t.bg, hover: t.tile || t.surface, actionBg: t.mention, actionFill: t.action, bar: t.surface, onBar: t.text }[role] || t.text;
  }

  /** Aerith's shapes: buttons on round tiles, a round glowing input, rounded rows and menus, dashed lines. */
  function aerithShapes(t) {
    return "\n#chat-tools-wrap .chat-tool-btn{background:" + t.tile + ";border-radius:10px;margin:0 .15em}" +
      "\n#chat-tools-wrap .chat-tool-btn .btn-icon{opacity:.75}" +
      "\n#chat-input-control{background:" + t.field + " !important;color:" + t.text + " !important;border:2px solid " + t.fieldBorder + " !important;border-radius:12px !important}" +
      "\n#chat-input-control:focus{box-shadow:0 0 6px " + t.fieldBorder + " !important}" +
      "\n#chat-input-control::placeholder{color:" + t.dim + " !important}" +
      "\n.msg-chat.msg-highlight,.msg-chat.msg-tagged{border-radius:0 8px 8px 0}" +
      "\n.chat-menu .chat-menu-inner{border-radius:12px !important}" +
      "\n#chat-user-info .floating-window{border-radius:12px}" +
      "\n#chat-user-info .user-identity,#chat-user-info .user-info h5,.chat-menu .toolbar{border-bottom:1px dashed " + t.dash + " !important}" +
      "\n#chat-user-info .chat-tool-btn{background:" + t.tile + ";border-radius:10px;margin:.15em}" +
      "\n#chat-user-info button:not(.glorp-choice),.chat-menu .btn,#glorp-settings .btn{border-radius:9999px !important;background:" + t.tile + " !important;border-color:" + t.tile + " !important;color:" + t.text + " !important}" +
      "\n.chat-menu input.form-control,.chat-menu select{border-radius:9999px !important;background:" + t.field + " !important}";
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

  // ---- Updates ----------------------------------------------------------------------
  // A script can't replace itself: this finds the published version, and its
  // link opens the manager's own update page.
  var installed = (typeof GM_info !== "undefined" && GM_info.script && GM_info.script.version) || "?";
  var latest = null; // the published version once checked
  function newer(a, b) {
    var x = String(a).split("."), y = String(b).split(".");
    for (var i = 0; i < Math.max(x.length, y.length); i++) {
      var d = (parseInt(x[i], 10) || 0) - (parseInt(y[i], 10) || 0);
      if (d) return d > 0;
    }
    return false;
  }
  function checkUpdate() {
    return request("GET", SCRIPT_URL + "?t=" + Date.now()).then(function (r) {
      var m = r.status === 200 && /@version\s+(\S+)/.exec(r.text);
      if (!m) throw new Error("no version");
      latest = m[1];
      renderUpdate();
      return newer(latest, installed);
    });
  }
  function updateLink(text) {
    return el("a", { href: SCRIPT_URL, target: "_blank", rel: "noopener", text: text });
  }
  function renderUpdate(note) {
    var box = panel && panel.querySelector("#glorp-update");
    if (!box) return;
    box.textContent = "";
    var line = el("p", { "class": "glorp-note" });
    if (note) line.textContent = note;
    else if (latest && newer(latest, installed)) line.append("Version " + latest + " is out (you have " + installed + "). ", updateLink("Update"));
    else if (latest) line.textContent = "Up to date (" + installed + ").";
    else line.textContent = "Version " + installed + ".";
    box.appendChild(line);
    box.appendChild(el("div", { "class": "form-group" }, [el("button", { "class": "btn btn-dark", text: "Check for updates", onclick: function () {
      renderUpdate("Checking…");
      checkUpdate().catch(function () { renderUpdate("Couldn't check for updates. Try again in a bit."); });
    } })]));
  }
  /** A line in chat, like the site's own notices, when there's a newer version. */
  function tellChat() {
    var tries = 0;
    (function wait() {
      var lines = document.querySelector("#chat-output-frame .chat-lines");
      // After the site's own first lines (the connection notice), so it isn't lost above them.
      if (!lines || !lines.children.length) { if (++tries < 60) setTimeout(wait, 500); return; }
      var bottom = lines.scrollHeight - lines.scrollTop - lines.clientHeight < 30;
      var text = el("span", { "class": "text" }, ["Glorp userscript " + latest + " is out (you have " + installed + "). ", updateLink("Update")]);
      lines.appendChild(el("div", { "class": "msg-chat msg-info" }, [text]));
      if (bottom) lines.scrollTop = lines.scrollHeight;
    })();
  }

  // ---- The site's user menu ------------------------------------------------------
  // Two parts added to the menu a name opens (click or right-click), as Aerith's
  // themes for the site have them: the Glorp icon you give them (synced), and
  // chat's own tag (colour and note), set through chat's /tag and /untag.
  var ICON_CHOICES = ["💗", "⭐", "👑", "🍃", "🔥", "💀", "💩", "🐸", "🤡", "🎮"];
  var TAG_COLOURS = [["green", "#61bd4f"], ["yellow", "#f2d600"], ["orange", "#ffab4a"], ["red", "#eb5a46"], ["purple", "#c377e0"],
    ["blue", "#0079bf"], ["sky", "#00c2e0"], ["lime", "#51e898"], ["pink", "#ff80ce"], ["black", "#4d4d4d"]];
  /**
   * Runs a chat command as if typed and sent, leaving whatever you were
   * writing in place. chat-gui sends on a keypress with key code 13; that
   * runs in the page's own world (the site allows inline scripts), where
   * chat's handlers see the key code.
   */
  function chatCommand(text) {
    if (!document.getElementById("chat-input-control")) return;
    var code = "(function (text) {" +
      "var input = document.getElementById('chat-input-control'); var draft = input.value; input.value = text;" +
      "var e = new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true });" +
      "Object.defineProperty(e, 'keyCode', { get: function () { return 13; } });" +
      "Object.defineProperty(e, 'which', { get: function () { return 13; } });" +
      "input.dispatchEvent(e); input.value = draft; input.dispatchEvent(new Event('input', { bubbles: true }));" +
      "})(" + JSON.stringify(text) + ");";
    var script = document.createElement("script");
    script.textContent = code;
    (document.head || document.documentElement).appendChild(script);
    script.remove();
  }
  /** The colour and note chat has for [nick], read off their messages. */
  function chatTag(nick) {
    var line = document.querySelector('.msg-user[data-username="' + nick.toLowerCase() + '"][class*="msg-tagged-"]');
    if (!line) return null;
    var m = /msg-tagged-([a-z]+)/.exec(line.className);
    return m ? { colour: m[1], note: line.getAttribute("title") || "" } : null;
  }
  function renderUserInfo(menu) {
    var name = menu.querySelector(".user-identity .username");
    var nick = name && name.textContent.trim();
    var old = menu.querySelector("#glorp-userinfo");
    if (old) old.remove();
    if (!nick) return;
    var key = nick.toLowerCase();
    var box = el("div", { id: "glorp-userinfo" });
    // Your icon for them.
    box.appendChild(el("h5", { text: "Your icon for them" }));
    var now = icons()[key];
    var row = el("div", { "class": "glorp-choices" });
    ICON_CHOICES.concat(now && ICON_CHOICES.indexOf(now) < 0 ? [now] : []).forEach(function (icon) {
      row.appendChild(el("button", { type: "button", "class": "glorp-choice" + (icon === now ? " picked" : ""), title: icon === now ? "Remove" : "Use this", text: icon, onclick: function () {
        var next = Object.assign({}, icons());
        if (next[key] === icon) delete next[key]; else next[key] = icon;
        set("nameIcons", next);
        renderUserInfo(menu);
      } }));
    });
    box.appendChild(row);
    // Chat's tag.
    var tag = chatTag(nick);
    box.appendChild(el("h5", { text: "Tag" }));
    var picked = tag ? tag.colour : null;
    var swatches = el("div", { "class": "glorp-choices" });
    var note = el("input", { "class": "form-control", placeholder: "Why? (a note)", value: tag ? tag.note : "" });
    TAG_COLOURS.forEach(function (c) {
      var b = el("button", { type: "button", "class": "glorp-choice glorp-swatch" + (c[0] === picked ? " picked" : ""), title: c[0], style: "background:" + c[1] + ";color:" + c[1] });
      b.addEventListener("click", function () {
        picked = c[0];
        swatches.querySelectorAll(".glorp-swatch").forEach(function (x) { x.classList.toggle("picked", x === b); });
      });
      swatches.appendChild(b);
    });
    box.appendChild(swatches);
    box.appendChild(el("div", { "class": "glorp-tagrow" }, [
      note,
      el("button", { type: "button", "class": "btn btn-dark", text: "Set", onclick: function () {
        if (!picked) return;
        chatCommand("/tag " + nick + " " + picked + (note.value.trim() ? " " + note.value.trim() : ""));
        setTimeout(function () { renderUserInfo(menu); }, 300);
      } }),
      tag ? el("button", { type: "button", "class": "btn btn-dark", text: "Remove", onclick: function () {
        chatCommand("/untag " + nick);
        setTimeout(function () { renderUserInfo(menu); }, 300);
      } }) : null,
    ]));
    var info = menu.querySelector(".user-info");
    var messages = info && Array.prototype.find.call(info.querySelectorAll(":scope > h5"), function (h) { return /^Messages/.test(h.textContent.trim()); });
    if (messages) info.insertBefore(box, messages);
    else if (info) info.appendChild(box);
  }
  function watchUserInfo() {
    var menu = document.getElementById("chat-user-info");
    if (!menu) return;
    menu.classList.add("glorp-menu");
    var shownFor = null;
    new MutationObserver(function () {
      var open = menu.classList.contains("active");
      var nick = (menu.querySelector(".user-identity .username") || {}).textContent;
      if (!open) { shownFor = null; return; }
      if (nick && nick !== shownFor) { shownFor = nick; renderUserInfo(menu); }
    }).observe(menu, { attributes: true, attributeFilter: ["class"], subtree: true, childList: true, characterData: true });
  }

  // ---- Mentions ---------------------------------------------------------------------
  // What polecat.me's chat logger has of messages naming you, newest first,
  // in a menu of its own beside the whisper button. polecat allows 60
  // requests a minute; past that, or on a 429, it isn't asked until it says.
  var POLECAT = "https://polecat.me";
  var polecatRest = 0;
  var mentions = { nick: null, lines: [], loading: false, ended: false, failed: false };
  var emoteNames = null;
  function loadEmoteNames() {
    if (emoteNames) return Promise.resolve(emoteNames);
    var cdn = site === "omniliberal.dev" ? "https://cdn.omniliberal.dev" : "https://cdn.destiny.gg";
    return request("GET", cdn + "/emotes/emotes.json").then(function (r) {
      emoteNames = new Set(JSON.parse(r.text).map(function (e) { return e.prefix; }));
      return emoteNames;
    }).catch(function () { emoteNames = new Set(); return emoteNames; });
  }
  function myNick() {
    var input = document.getElementById("chat-input-control");
    var m = input && /^Write something (\S+) \.\.\.$/.exec(input.placeholder || "");
    return m ? m[1] : null;
  }
  function polecatPage(nick, offset) {
    if (Date.now() < polecatRest) return Promise.reject(new Error("resting"));
    return new Promise(function (resolve, reject) {
      GM.xmlHttpRequest({
        method: "GET", url: POLECAT + "/api/mentions/" + encodeURIComponent(nick) + "?size=50&offset=" + offset, timeout: 15000,
        onload: function (r) {
          var head = r.responseHeaders || "";
          var left = /ratelimit-remaining:\s*(\d+)/i.exec(head);
          if (r.status === 429 || (left && left[1] === "0")) {
            var wait = /retry-after:\s*(\d+)/i.exec(head) || /ratelimit-reset:\s*(\d+)/i.exec(head);
            polecatRest = Date.now() + Math.min(3600, wait ? +wait[1] : 60) * 1000;
          }
          if (r.status !== 200) return reject(new Error(String(r.status)));
          try { resolve(JSON.parse(r.responseText)); } catch (e) { reject(e); }
        },
        onerror: function () { reject(new Error("network")); },
        ontimeout: function () { reject(new Error("timeout")); },
      });
    });
  }
  function moreMentions() {
    var m = mentions;
    if (!m.nick || m.loading || m.ended) return;
    m.loading = true; m.failed = false; renderMentions();
    Promise.all([polecatPage(m.nick, m.lines.length), loadEmoteNames()]).then(function (got) {
      if (m !== mentions) return;
      var page = got[0].sort(function (a, b) { return b.date - a.date; });
      var known = new Set(m.lines.map(function (l) { return l.id; }));
      page.forEach(function (l) { if (!known.has(l.id)) m.lines.push(l); });
      if (page.length < 50) m.ended = true;
    }).catch(function () { if (m === mentions) m.failed = true; }).then(function () {
      if (m !== mentions) return;
      m.loading = false; renderMentions();
    });
  }
  function showMentionsOf(nick) {
    mentions = { nick: nick, lines: [], loading: false, ended: false, failed: false };
    renderMentions();
    moreMentions();
  }
  /** One logged message in the chat's own markup, emotes as the site's emote classes draw them. */
  function mentionLine(l) {
    var text = el("span", { "class": "text" });
    (l.text || "").split(/(\s+)/).forEach(function (word) {
      if (emoteNames && emoteNames.has(word)) text.appendChild(el("div", { "class": "emote " + word, title: word, text: word }));
      else text.appendChild(document.createTextNode(word));
    });
    var when = new Date(l.date);
    var line = el("div", { "class": "msg-chat msg-user glorp-mention-line", title: "Open the chat around this on rustlesearch.dev" }, [
      el("time", { "class": "time", text: ("0" + when.getHours()).slice(-2) + ":" + ("0" + when.getMinutes()).slice(-2) + " " }),
      el("a", { "class": "user", text: l.nick }), el("span", { "class": "ctrl", text: ": " }), text,
    ]);
    line.addEventListener("click", function () {
      var u = "https://rustlesearch.dev/surrounds?channel=Destinygg&date=" + encodeURIComponent(new Date(l.date).toISOString()) + "&username=" + encodeURIComponent(l.nick);
      window.open(u, "_blank", "noopener");
    });
    return line;
  }
  function renderMentions() {
    var box = mentionsPanel && mentionsPanel.querySelector("#glorp-mentions-list");
    if (!box) return;
    var m = mentions;
    box.textContent = "";
    if (!m.nick) { box.appendChild(el("p", { "class": "glorp-note", text: "Type a name above to see the messages naming it." })); return; }
    var day = null;
    m.lines.forEach(function (l) {
      var d = new Date(l.date).toDateString();
      if (d !== day) { day = d; box.appendChild(el("div", { "class": "glorp-day", text: d })); }
      box.appendChild(mentionLine(l));
    });
    var end = m.loading ? "Loading…" : m.failed ? "polecat.me isn't answering, or asked to wait a minute. Scroll again to retry."
      : m.ended ? (m.lines.length ? "No older mentions." : "No mentions of " + m.nick + ".") : "";
    if (end) box.appendChild(el("p", { "class": "glorp-note", text: end }));
  }
  var mentionsPanel = null;
  function buildMentions() {
    var nameIn = el("input", { "class": "form-control", placeholder: "Name", spellcheck: "false" });
    var list = el("div", { id: "glorp-mentions-list", "class": "chat-lines" });
    mentionsPanel = el("div", { id: "glorp-mentions", "class": "chat-menu left glorp-menu" }, [
      el("div", { "class": "chat-menu-inner" }, [
        el("div", { "class": "toolbar" }, [el("h5", {}, [el("span", { text: "Mentions" }), el("i", { "class": "chat-menu-close", onclick: function () { closeMentions(); } })])]),
        el("form", { "class": "glorp-mentions-name", onsubmit: function (e) { e.preventDefault(); var n = nameIn.value.trim(); if (n) showMentionsOf(n); } }, [nameIn, el("button", { "class": "btn btn-dark", type: "submit", text: "Show" })]),
        el("div", { "class": "scrollable", style: "overflow-y:auto;flex:1" }, [list]),
        el("p", { "class": "glorp-note glorp-credit" }, ["From ", el("a", { href: POLECAT + "/mentions", target: "_blank", rel: "noopener", text: "polecat.me" }), "'s chat logs"]),
      ]),
    ]);
    var scroller = mentionsPanel.querySelector(".scrollable");
    scroller.addEventListener("scroll", function () {
      if (scroller.scrollTop + scroller.clientHeight > scroller.scrollHeight - 200) {
        if (mentions.failed) mentions.failed = false;
        moreMentions();
      }
    }, { passive: true });
    document.getElementById("chat").appendChild(mentionsPanel);
    mentionsPanel._name = nameIn;
  }
  var mentionsButton = null;
  function closeMentions() {
    if (mentionsPanel) mentionsPanel.classList.remove("active");
    if (mentionsButton) mentionsButton.classList.remove("active");
    if (searchPanel) searchPanel.classList.remove("active");
    if (searchButton) searchButton.classList.remove("active");
  }
  function toggleMentions() {
    var open = !mentionsPanel.classList.contains("active");
    document.querySelectorAll("#chat .chat-menu.active").forEach(function (m) { if (m !== mentionsPanel) m.classList.remove("active"); });
    if (panel) { panel.classList.remove("active"); }
    var g = document.getElementById("chat-glorp-btn"); if (g) g.classList.remove("active");
    if (searchButton) searchButton.classList.remove("active");
    mentionsPanel.classList.toggle("active", open);
    mentionsButton.classList.toggle("active", open);
    if (!open) return;
    var me = myNick();
    if (!mentions.nick && me) { mentionsPanel._name.value = me; showMentionsOf(me); }
    else if (!mentions.nick) renderMentions();
  }

  // ---- Log search ----------------------------------------------------------------------
  // rustlesearch.dev's chat logs, as the app's log search: by name, words or
  // both, newest first, a page at a time. Its anonymous use is limited per
  // address (it says so in a 429), so a page is only asked for when you
  // search or scroll to the end.
  var RUSTLE = "https://api-v2.rustlesearch.dev";
  var search = { name: "", words: "", lines: [], loading: false, ended: false, failed: null };
  var searchPanel = null, searchButton = null;
  function moreSearch() {
    var q = search;
    if ((!q.name && !q.words) || q.loading || q.ended) return;
    q.loading = true; q.failed = null; renderSearch();
    var url = RUSTLE + "/anon/search?channel=Destinygg" + (q.name ? "&username=" + encodeURIComponent(q.name) : "") +
      (q.words ? "&text=" + encodeURIComponent(q.words) : "") +
      (q.lines.length ? "&search_after=" + q.lines[q.lines.length - 1].searchAfter : "");
    Promise.all([request("GET", url), loadEmoteNames()]).then(function (got) {
      if (q !== search) return;
      var r = got[0];
      if (r.status === 429) { q.failed = "rustlesearch.dev's daily limit for this address is used up. Try again tomorrow."; return; }
      var body = JSON.parse(r.text);
      if (r.status !== 200 || body.error) { q.failed = body.error || "rustlesearch.dev answered " + r.status + "."; return; }
      var page = ((body.data || {}).messages || []).map(function (m) {
        return { id: m.searchAfter, searchAfter: m.searchAfter, date: Date.parse(m.ts), ts: m.ts, nick: m.username, text: m.text };
      });
      page.forEach(function (l) { q.lines.push(l); });
      if (page.length < 100) q.ended = true;
    }).catch(function () { if (q === search) q.failed = "Couldn't reach rustlesearch.dev."; }).then(function () {
      if (q !== search) return;
      q.loading = false; renderSearch();
    });
  }
  function runSearch(name, words) {
    search = { name: name, words: words, lines: [], loading: false, ended: false, failed: null };
    var box = searchPanel && searchPanel.querySelector(".scrollable"); if (box) box.scrollTop = 0;
    renderSearch();
    moreSearch();
  }
  function renderSearch() {
    var box = searchPanel && searchPanel.querySelector("#glorp-search-list");
    if (!box) return;
    var q = search;
    box.textContent = "";
    if (!q.name && !q.words) {
      box.appendChild(el("p", { "class": "glorp-note", text: "Search chat's logs by name, words or both, newest first. In words: a | b for either, \"a phrase\" for exact words, -word to leave one out. Click a line to see the chat around it." }));
      return;
    }
    var day = null;
    q.lines.forEach(function (l) {
      var d = new Date(l.date).toDateString();
      if (d !== day) { day = d; box.appendChild(el("div", { "class": "glorp-day", text: d })); }
      box.appendChild(mentionLine(l));
    });
    var end = q.loading ? "Loading…" : q.failed ? q.failed
      : q.ended ? (q.lines.length ? "No older messages." : "No messages found.") : "";
    if (end) box.appendChild(el("p", { "class": "glorp-note", text: end }));
  }
  function buildSearch() {
    var nameIn = el("input", { "class": "form-control", placeholder: "Name", spellcheck: "false" });
    var wordsIn = el("input", { "class": "form-control", placeholder: "Words", spellcheck: "false" });
    searchPanel = el("div", { id: "glorp-search", "class": "chat-menu left glorp-menu glorp-logs" }, [
      el("div", { "class": "chat-menu-inner" }, [
        el("div", { "class": "toolbar" }, [el("h5", {}, [el("span", { text: "Log search" }), el("i", { "class": "chat-menu-close", onclick: function () { closeMentions(); } })])]),
        el("form", { "class": "glorp-mentions-name", onsubmit: function (e) { e.preventDefault(); runSearch(nameIn.value.trim(), wordsIn.value.trim()); } }, [
          nameIn, wordsIn, el("button", { "class": "btn btn-dark", type: "submit", text: "Search" }),
        ]),
        el("div", { "class": "scrollable", style: "overflow-y:auto;flex:1" }, [el("div", { id: "glorp-search-list", "class": "chat-lines" })]),
        el("p", { "class": "glorp-note glorp-credit" }, ["Search by ", el("a", { href: "https://rustlesearch.dev/", target: "_blank", rel: "noopener", text: "rustlesearch.dev" })]),
      ]),
    ]);
    var scroller = searchPanel.querySelector(".scrollable");
    scroller.addEventListener("scroll", function () {
      if (!search.failed && scroller.scrollTop + scroller.clientHeight > scroller.scrollHeight - 200) moreSearch();
    }, { passive: true });
    document.getElementById("chat").appendChild(searchPanel);
    renderSearch();
  }
  function toggleSearch() {
    var open = !searchPanel.classList.contains("active");
    document.querySelectorAll("#chat .chat-menu.active").forEach(function (m) { if (m !== searchPanel) m.classList.remove("active"); });
    [mentionsButton, document.getElementById("chat-glorp-btn")].forEach(function (b) { if (b) b.classList.remove("active"); });
    searchPanel.classList.toggle("active", open);
    searchButton.classList.toggle("active", open);
    if (open) { var f = searchPanel.querySelector("input"); if (f) f.focus(); }
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
    panel = el("div", { id: "glorp-settings", "class": "chat-menu right glorp-menu" }, [
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

    form.appendChild(el("h4", { text: "This userscript" }));
    form.appendChild(el("div", { id: "glorp-update" }));
    renderUpdate();
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
          "<p class='quiet'>Without a manager that can, <a href='https://strawwaffle.dev/glorp/signin/?site=" + encodeURIComponent(site) + "'>Glorp's sign-in page</a> shows how to copy them by hand.</p>";
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
    document.head.appendChild(extraStyle);
    var style = document.createElement("style");
    style.textContent =
      "#chat-glorp-btn .btn-icon{background:url(" + ICON + ") center/contain no-repeat}" +
      // An @ in a circle, drawn as the site's own icons are (white strokes, faded until hovered).
      "#chat-glorp-mentions-btn .btn-icon{background:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='12' cy='12' r='4'/%3E%3Cpath d='M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8'/%3E%3C/svg%3E\") center/contain no-repeat}" +
      // A magnifying glass, as the site's icons are drawn.
      "#chat-glorp-search-btn .btn-icon{background:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='11' cy='11' r='7'/%3E%3Cpath d='m21 21-4.3-4.3'/%3E%3C/svg%3E\") center/contain no-repeat}" +
      "#glorp-search .chat-menu-inner{display:flex;flex-direction:column}" +
      "#glorp-search .glorp-mentions-name{display:flex;gap:.4em;margin:.6em}#glorp-search .glorp-mentions-name input{flex:1;min-width:0;border-radius:.25em;padding:.3em}" +
      "#glorp-search .chat-lines{overflow:visible;height:auto}" +
      "#glorp-search .glorp-mention-line{cursor:pointer;padding:.15em .6em}#glorp-search .glorp-mention-line:hover{background:rgba(127,127,127,.12)}" +
      "#glorp-search .glorp-day{text-align:center;font-size:.85em;opacity:.6;margin:.6em 0 .2em}" +
      "#glorp-search .glorp-note{opacity:.7;font-size:.9em;margin:.6em}#glorp-search .glorp-credit{margin:.4em .6em}" +
      "#glorp-mentions .chat-menu-inner{display:flex;flex-direction:column}" +
      "#glorp-mentions .glorp-mentions-name{display:flex;gap:.4em;margin:.6em}#glorp-mentions .glorp-mentions-name input{flex:1;min-width:0;border-radius:.25em;padding:.3em}" +
      "#glorp-mentions .chat-lines{overflow:visible;height:auto}" +
      "#glorp-mentions .glorp-mention-line{cursor:pointer;padding:.15em .6em}#glorp-mentions .glorp-mention-line:hover{background:rgba(127,127,127,.12)}" +
      "#glorp-mentions .glorp-day{text-align:center;font-size:.85em;opacity:.6;margin:.6em 0 .2em}" +
      "#glorp-mentions .glorp-note{opacity:.7;font-size:.9em;margin:.6em}#glorp-mentions .glorp-credit{margin:.4em .6em}" +
      // As the site lays out its own settings menu (menus/_settings.scss).
      "#glorp-settings-form{margin:.9em 0}" +
      "#glorp-settings .form-group{margin:.9em;display:block;position:relative}" +
      "#glorp-settings h4{font-size:.9em;margin-top:1.8em;margin-bottom:.9em;padding-left:.9em;color:#494949;text-transform:uppercase;font-weight:600}" +
      "#glorp-settings label{display:inline-block;font-weight:normal;max-width:100%;margin-bottom:.6em}" +
      "#glorp-settings .checkbox label{margin-bottom:0;cursor:pointer;display:flex;align-items:center}" +
      "#glorp-settings .checkbox input{margin:0 .3em 0 0}" +
      "#glorp-settings select,#glorp-settings input.form-control{border-radius:.25em;padding:.3em;width:100%;box-sizing:border-box}" +
      ".glorp-menu .btn{background:#222;color:#dedede;border:1px solid #333;border-radius:.25em;padding:.35em .8em;cursor:pointer}" +
      ".glorp-menu .btn:hover{background:#333}" +
      ".glorp-menu .scrollable{scrollbar-width:thin;scrollbar-color:rgba(255,255,255,.4) transparent}" +
      // In the site's user menu: your icon for them, and chat's tag.
      "#glorp-userinfo h5{border-bottom:none !important}" +
      "#glorp-userinfo .glorp-choices{display:flex;flex-wrap:wrap;gap:.3em;padding:0 .6em .5em}" +
      "#glorp-userinfo .glorp-choice{width:2em;height:2em;display:inline-flex;align-items:center;justify-content:center;border-radius:.5em;background:rgba(127,127,127,.18);cursor:pointer;border:2px solid transparent;font-size:1em;padding:0}" +
      "#glorp-userinfo .glorp-choice.picked{border-color:currentColor}" +
      "#glorp-userinfo .glorp-swatch{width:1.7em;height:1.7em;border-radius:.45em}" +
      "#glorp-userinfo .glorp-tagrow{display:flex;gap:.4em;padding:0 .6em .6em;align-items:center}" +
      "#glorp-userinfo .glorp-tagrow input{flex:1;min-width:0;border-radius:9999px;padding:.3em .7em}" +
      "#glorp-userinfo .btn{border-radius:9999px;padding:.3em .9em}" +
      "#glorp-settings .glorp-note{color:#999;font-size:.9em;margin:.3em .9em}" +
      "#glorp-settings .glorp-row{display:flex;justify-content:space-between;gap:1em}" +
      "#glorp-settings .glorp-add{display:flex;gap:.4em}#glorp-settings .glorp-add input{min-width:0;flex:1}#glorp-settings .glorp-add input+input{flex:0 0 5em}" +
      "#glorp-settings .btn{margin:.2em .4em .2em 0}" +
      "#glorp-settings .glorp-qr{background:#fff;padding:6px;border-radius:6px;max-width:220px}#glorp-settings .glorp-qr svg{display:block;width:100%;height:auto}" +
      "#glorp-settings .glorp-qr code{display:block;color:#111;font-size:10px;word-break:break-all;margin-top:4px}" +
      "#glorp-stuck{position:absolute;top:0;left:0;right:0;z-index:5;background:inherit;border-bottom:1px solid #333;display:flex;align-items:flex-start}#glorp-stuck[hidden]{display:none}" +
      "#glorp-stuck .glorp-stuck-line{flex:1;min-width:0}#glorp-stuck .glorp-stuck-close{cursor:pointer;padding:.3em .6em;opacity:.6}";
    document.head.appendChild(style);

    var button = el("a", { id: "chat-glorp-btn", "class": "chat-tool-btn", title: "Glorp", "aria-label": "Glorp" }, [el("i", { "class": "btn-icon" })]);
    groups[1].insertBefore(button, groups[1].firstChild);
    buildPanel();
    mentionsButton = el("a", { id: "chat-glorp-mentions-btn", "class": "chat-tool-btn", title: "Mentions", "aria-label": "Mentions" }, [el("i", { "class": "btn-icon" })]);
    var whisper = document.getElementById("chat-whisper-btn");
    if (whisper && whisper.parentNode === groups[0]) groups[0].insertBefore(mentionsButton, whisper.nextSibling);
    else groups[0].appendChild(mentionsButton);
    buildMentions();
    mentionsButton.addEventListener("click", function (e) { e.stopPropagation(); toggleMentions(); });
    searchButton = el("a", { id: "chat-glorp-search-btn", "class": "chat-tool-btn", title: "Log search", "aria-label": "Log search" }, [el("i", { "class": "btn-icon" })]);
    groups[0].insertBefore(searchButton, mentionsButton.nextSibling);
    buildSearch();
    searchButton.addEventListener("click", function (e) { e.stopPropagation(); toggleSearch(); });
    button.addEventListener("click", function (e) {
      e.stopPropagation();
      // As the site's own menus: one open at a time.
      document.querySelectorAll("#chat .chat-menu.active").forEach(function (m) { if (m !== panel) m.classList.remove("active"); });
      panel.classList.toggle("active");
      button.classList.toggle("active", panel.classList.contains("active"));
      if (panel.classList.contains("active")) syncSoon();
      closeMentions();
    });
    ["chat-emoticon-btn", "chat-whisper-btn", "chat-settings-btn", "chat-users-btn"].forEach(function (id) {
      var b = document.getElementById(id);
      if (b) b.addEventListener("click", function () { panel.classList.remove("active"); button.classList.remove("active"); closeMentions(); });
    });
    var output = document.getElementById("chat-output-frame");
    if (output) output.addEventListener("click", function () { panel.classList.remove("active"); button.classList.remove("active"); closeMentions(); });

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
    watchUserInfo();
    apply();
    syncSoon();
    checkUpdate().then(function (isNewer) { if (isNewer) tellChat(); }).catch(function () {});
    return true;
  }

  load().then(function () {
    // The theme on every page of the site (bigscreen's header and chat column too), not only in chat.
    (document.head || document.documentElement).appendChild(themeStyle);
    applyTheme();
    // A change made in chat's Glorp panel (another frame) or another tab shows here at once.
    if (typeof GM_addValueChangeListener !== "undefined") {
      GM_addValueChangeListener("glorp", function (name, before, after, remote) {
        if (!remote || !after || typeof after !== "object") return;
        state = { settings: after.settings || {}, local: after.local || {} };
        apply();
      });
    }
    if (typeof GM_registerMenuCommand !== "undefined") {
      GM_registerMenuCommand("Sign in to Glorp", openSignIn);
      GM_registerMenuCommand("Check for updates", function () {
        checkUpdate().then(function (isNewer) {
          if (isNewer) window.open(SCRIPT_URL, "_blank");
          else alert("Glorp userscript is up to date (" + installed + ").");
        }).catch(function () { alert("Couldn't check for updates. Try again in a bit."); });
      });
    }
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
