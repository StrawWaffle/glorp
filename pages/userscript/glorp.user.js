// ==UserScript==
// @name         Glorp sign-in
// @namespace    https://strawwaffle.github.io/glorp/
// @version      1.2
// @description  Signs Glorp in from destiny.gg on a computer: shows the code Glorp scans, made from the site's login cookies, without the developer tools.
// @match        https://www.destiny.gg/*
// @match        https://www.omniliberal.dev/*
// @grant        GM_cookie
// @grant        GM.cookie
// @grant        GM_registerMenuCommand
// @require      https://raw.githubusercontent.com/StrawWaffle/glorp/7188e9b8b52cfc8829608c7cc163713e455f6291/pages/signin/qrcode.js#sha256=18ae399f81182bc9de916e9c77b195df20cc58d6f2d55a62b085a299f1bf1780
// @homepageURL  https://github.com/StrawWaffle/glorp/tree/main/pages/userscript
// @downloadURL  https://strawwaffle.github.io/glorp/userscript/glorp.user.js
// @updateURL    https://strawwaffle.github.io/glorp/userscript/glorp.user.js
// @run-at       document-idle
// @noframes
// ==/UserScript==

// destiny.gg keeps its login cookies (sid, rememberme) from web pages. A
// userscript manager can read them for a script that asks: Violentmonkey when
// HTTP-only cookies are allowed for this script and in its own settings,
// Tampermonkey in its beta. This script reads them only when you ask, turns
// them into the same code Glorp's sign-in page makes
// (glorp-signin:1:<site>:<sid>:<rememberme>), shows it until you close it or
// leave the tab, and sends nothing anywhere.
(function () {
  "use strict";

  var site = location.hostname.replace(/^www\./, "");
  var origin = location.origin;

  /** The cookie by name, through whichever cookie API the manager offers; null if it isn't readable. */
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
      } catch (e) {
        resolve(null);
      }
    });
    function pick(list) {
      var found = (list || []).filter(function (c) { return c.name === name && c.value; })[0];
      return found ? found.value : null;
    }
  }

  // Everything the script shows lives in a shadow root, apart from the site's styles.
  var host = null;
  function close() {
    if (host) host.remove();
    host = null;
  }

  function open() {
    close();
    host = document.createElement("div");
    var root = host.attachShadow({ mode: "closed" });
    root.innerHTML =
      "<style>" +
      ".back{position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:2147483647;display:flex;align-items:center;justify-content:center;font:14px/1.45 system-ui,sans-serif}" +
      ".box{background:#18191b;color:#edeef0;border:1px solid #363a3f;border-radius:10px;padding:20px;width:min(420px,calc(100vw - 32px));max-height:calc(100vh - 32px);overflow:auto}" +
      "h2{margin:0 0 8px;font-size:17px}p{margin:8px 0}.quiet{color:#9a9ea6}.problem{color:#ff8f8f}" +
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
    root.querySelector(".close").addEventListener("click", close);
    root.querySelector(".back").addEventListener("click", function (e) { if (e.target === e.currentTarget) close(); });
    document.body.appendChild(host);
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
          "<li>This script's own <b>Settings</b> tab: in the dashboard, open <b>Glorp sign-in</b> with its edit button.</li></ol>" +
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

  // Leaving the tab hides the code.
  document.addEventListener("visibilitychange", function () { if (document.hidden) close(); });

  if (typeof GM_registerMenuCommand !== "undefined") GM_registerMenuCommand("Sign in to Glorp", open);

  // And a link where the site lists your account.
  if (/^\/profile/.test(location.pathname)) {
    var link = document.createElement("a");
    link.href = "#";
    link.textContent = "Sign in to Glorp";
    link.style.cssText = "display:inline-block;margin:12px 0;color:#70b8ff";
    link.addEventListener("click", function (e) { e.preventDefault(); open(); });
    var main = document.querySelector("main") || document.body;
    main.insertBefore(link, main.firstChild);
  }
})();
