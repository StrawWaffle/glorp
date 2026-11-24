// System, light or dark for Glorp's pages, kept in this browser. Runs in the
// head, so the page never shows in the wrong colours first.
(function () {
  "use strict";
  var KEY = "glorp-theme";
  var root = document.documentElement;
  function saved() {
    try { return localStorage.getItem(KEY) || "system"; } catch (e) { return "system"; }
  }
  function apply(choice) {
    if (choice === "light" || choice === "dark") root.setAttribute("data-theme", choice);
    else root.removeAttribute("data-theme");
  }
  apply(saved());

  document.addEventListener("DOMContentLoaded", function () {
    var group = document.createElement("div");
    group.className = "theme-switch";
    group.setAttribute("role", "group");
    group.setAttribute("aria-label", "Colours");
    var buttons = [["system", "System"], ["light", "Light"], ["dark", "Dark"]].map(function (o) {
      var b = document.createElement("button");
      b.type = "button";
      b.textContent = o[1];
      b.addEventListener("click", function () {
        try { if (o[0] === "system") localStorage.removeItem(KEY); else localStorage.setItem(KEY, o[0]); } catch (e) {}
        apply(o[0]);
        mark(o[0]);
      });
      group.appendChild(b);
      return [o[0], b];
    });
    function mark(choice) {
      buttons.forEach(function (x) { x[1].setAttribute("aria-pressed", String(x[0] === choice)); });
    }
    mark(saved());
    document.body.appendChild(group);
  });
})();
