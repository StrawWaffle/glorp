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
  var confirm = document.getElementById("confirm");
  var shown = document.getElementById("shown");
  var ack = document.getElementById("ack");
  var reveal = document.getElementById("reveal");
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

  // What the site's cookies look like: sid is 32 lowercase hex characters;
  // rememberme is a long URL-encoded token (a couple of hundred characters of
  // letters, digits and %2B / %2F). Anything far from that is most likely the
  // wrong cell, the cookie's name, or the two values swapped.
  var SID = /^[0-9a-f]{26,64}$/i;
  var TOKEN = /^[A-Za-z0-9%+\/=_-]{80,}$/;

  function oddities(s, r) {
    if (r && s === r) return "sid and rememberme are the same. Copy each cookie's own Value.";
    if (TOKEN.test(s) && SID.test(r)) return "These look swapped: sid is the short one, rememberme the long one.";
    if (/^(sid|rememberme)$/i.test(s) || /^(sid|rememberme)$/i.test(r)) return "That's the cookie's name. Copy the Value column instead.";
    if (/^(true|false|\/|www\.)|destiny\.gg|omniliberal\.dev|^\d{4}-\d{2}-\d{2}/i.test(s + " " + r))
      return "That looks like another column (Domain, Path or Expires). Copy the Value column.";
    if (!SID.test(s)) return "sid doesn't look right: it's usually 32 letters and digits.";
    if (r && !TOKEN.test(r)) return "rememberme doesn't look right: it's usually a long code of a couple of hundred characters.";
    return null;
  }

  // Back to before: no code anywhere on the page, and the warning to agree to again.
  function hide() {
    code = null;
    qr.textContent = "";
    shown.hidden = true;
    confirm.hidden = false;
    ack.checked = false;
    reveal.disabled = true;
    result.hidden = true;
  }

  // The code is made only once the warning is agreed to; first the warning shows.
  // A warning about odd-looking values is shown once; pressing again with the
  // same values goes ahead anyway, in case the site changes its format.
  var warnedFor = null;
  // The values the warning step was opened for, so another press can point at
  // it instead of starting over.
  var openFor = null;

  function nudge() {
    confirm.classList.remove("nudge");
    void confirm.offsetWidth; // restart the animation
    confirm.classList.add("nudge");
    result.scrollIntoView({ block: "center", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
    ack.focus();
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    var s = clean(sid.value, "sid");
    var r = clean(rememberme.value, "rememberme");
    var values = s + "|" + r;
    // Pressed again while the warning step waits: point at it, keep its state.
    if (!result.hidden && !confirm.hidden && openFor === values) {
      nudge();
      return;
    }
    hide();
    problem.hidden = true;
    if (!s || fails(s) || fails(r)) {
      problem.textContent = !s ? "Copy the value of sid first." : "That doesn't look like a cookie's value. Copy just the Value column.";
      problem.hidden = false;
      return;
    }
    var odd = oddities(s, r);
    if (odd && warnedFor !== values) {
      warnedFor = values;
      problem.textContent = odd + " Press Make the code again to use them anyway.";
      problem.hidden = false;
      return;
    }
    openFor = values;
    result.hidden = false;
    result.scrollIntoView({ block: "center" });
    ack.focus();
  });

  ack.addEventListener("change", function () { reveal.disabled = !ack.checked; });

  reveal.addEventListener("click", function () {
    if (!ack.checked) return;
    var s = clean(sid.value, "sid");
    var r = clean(rememberme.value, "rememberme");
    code = "glorp-signin:1:" + site + ":" + s + ":" + r;
    var q = qrcode(0, "M");
    q.addData(code);
    q.make();
    qr.innerHTML = q.createSvgTag({ cellSize: 6, margin: 4, scalable: true });
    copy.textContent = "Copy it as text";
    confirm.hidden = true;
    shown.hidden = false;
  });

  document.getElementById("cancel").addEventListener("click", hide);
  document.getElementById("hide").addEventListener("click", hide);

  copy.addEventListener("click", function () {
    if (!code) return;
    navigator.clipboard.writeText(code).then(function () { copy.textContent = "Copied"; });
  });

  // Leaving the page or switching tabs hides the code.
  document.addEventListener("visibilitychange", function () { if (document.hidden) hide(); });
})();
