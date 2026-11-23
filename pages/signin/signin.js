// Turns destiny.gg's login cookies into a code for Glorp to scan:
// glorp-signin:1:<site>:<sid>:<rememberme> (read by readSignInCode in the app).
// Nothing is stored or sent; the page's policy allows no requests.
(function () {
  "use strict";

  // destiny.gg, or its test site with ?site=omniliberal.dev.
  var sites = { "destiny.gg": "https://www.destiny.gg", "omniliberal.dev": "https://www.omniliberal.dev" };
  var site = new URLSearchParams(location.search).get("site");
  if (!sites[site]) site = "destiny.gg";
  var origin = sites[site];
  document.querySelectorAll(".site-name").forEach(function (el) { el.textContent = site; });
  document.querySelectorAll(".site-origin").forEach(function (el) { el.textContent = origin; });
  document.getElementById("site-link").href = origin + "/login";

  var form = document.getElementById("form");
  var sid = document.getElementById("sid");
  var rememberme = document.getElementById("rememberme");
  var problem = document.getElementById("problem");
  var result = document.getElementById("result");
  var qr = document.getElementById("qr");
  var copy = document.getElementById("copy");
  var code = null;

  // Forgives "sid=" prefixes, quotes and spaces copied along with a value.
  function clean(value, name) {
    return value.trim().replace(new RegExp("^" + name + "=", "i"), "").replace(/^"|"$/g, "").trim();
  }

  function fails(value) {
    return /[\s;:]/.test(value);
  }

  function hide() {
    code = null;
    qr.textContent = "";
    result.hidden = true;
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    var s = clean(sid.value, "sid");
    var r = clean(rememberme.value, "rememberme");
    problem.hidden = true;
    if (!s || fails(s) || fails(r)) {
      problem.textContent = !s ? "Copy the value of sid first." : "That doesn't look like a cookie's value: copy just the Value column.";
      problem.hidden = false;
      hide();
      return;
    }
    code = "glorp-signin:1:" + site + ":" + s + ":" + r;
    var q = qrcode(0, "M");
    q.addData(code);
    q.make();
    qr.innerHTML = q.createSvgTag({ cellSize: 6, margin: 4, scalable: true });
    copy.textContent = "Copy it as text";
    result.hidden = false;
    result.scrollIntoView({ block: "center" });
  });

  document.getElementById("hide").addEventListener("click", hide);

  copy.addEventListener("click", function () {
    if (!code) return;
    navigator.clipboard.writeText(code).then(function () { copy.textContent = "Copied"; });
  });

  // Leaving the page or switching tabs hides the code.
  document.addEventListener("visibilitychange", function () { if (document.hidden) hide(); });
})();
