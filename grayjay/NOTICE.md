# Notice

This module runs Grayjay source plugins. Parts of it come from Grayjay for
Android by FUTO (https://gitlab.futo.org/videostreaming/grayjay, mirrored at
https://github.com/futo-org/grayjay-android) and are under FUTO's Source First
License 1.1, in `LICENSE.md` beside this file:

- `js/polyfil.js` and `js/source.js`, Grayjay's plugin prelude, unmodified.
- The host in `src/` and `js/host.js` follow the interface of Grayjay's plugin
  engine and packages (Bridge, Http, Utilities, DOMParser).

**Modified:** this is not Grayjay's engine. It is a smaller host on QuickJS
rather than V8, written for Glorp, providing only what the plugins Glorp uses
need. DOMParser is a stub, Browser isn't provided, and there is no login.

The plugins themselves are not part of Glorp: they are downloaded from FUTO's
plugin site at run time, checked against their published signatures, and keep
their own licences.
