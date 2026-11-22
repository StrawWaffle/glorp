// Glorp's stand-in for the packages Grayjay's app gives plugins (Bridge,
// Http, Utilities, DOMParser), over the few host functions on `__host`.
// Values cross into Kotlin as JSON and base64 strings.
// Evaluated before polyfil.js and source.js, as Grayjay loads its packages first.

var __b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function __bytesToB64(bytes) {
    var out = "", i;
    for (i = 0; i + 2 < bytes.length; i += 3) {
        var n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
        out += __b64chars[n >> 18 & 63] + __b64chars[n >> 12 & 63] + __b64chars[n >> 6 & 63] + __b64chars[n & 63];
    }
    if (i < bytes.length) {
        var rest = bytes.length - i;
        var m = (bytes[i] << 16) | (rest > 1 ? bytes[i + 1] << 8 : 0);
        out += __b64chars[m >> 18 & 63] + __b64chars[m >> 12 & 63] + (rest > 1 ? __b64chars[m >> 6 & 63] : "=") + "=";
    }
    return out;
}
function __b64ToBytes(text) {
    var clean = text.replace(/[^A-Za-z0-9+/]/g, "");
    var out = new Uint8Array(Math.floor(clean.length * 3 / 4));
    var o = 0;
    for (var i = 0; i < clean.length; i += 4) {
        var n = (__b64chars.indexOf(clean[i]) << 18) | (__b64chars.indexOf(clean[i + 1]) << 12) |
            ((__b64chars.indexOf(clean[i + 2]) & 63) << 6) | (__b64chars.indexOf(clean[i + 3]) & 63);
        if (o < out.length) out[o++] = n >> 16 & 255;
        if (o < out.length && i + 2 < clean.length) out[o++] = n >> 8 & 255;
        if (o < out.length && i + 3 < clean.length) out[o++] = n & 255;
    }
    return out;
}
function __isBytes(v) { return v != null && typeof v === "object" && ArrayBuffer.isView(v); }
function __body(v) {
    if (v == null) return { body: null, bodyIsBytes: false };
    if (__isBytes(v)) return { body: __bytesToB64(new Uint8Array(v.buffer, v.byteOffset, v.byteLength)), bodyIsBytes: true };
    return { body: String(v), bodyIsBytes: false };
}
function __response(json, bytes) {
    var r = JSON.parse(json);
    if (r.error) throw new Error(r.error);
    if (bytes && r.body != null) r.body = __b64ToBytes(r.body);
    return r;
}

var console = {
    log: function () { __host.log(Array.prototype.join.call(arguments, " ")); },
    info: function () { __host.log(Array.prototype.join.call(arguments, " ")); },
    debug: function () {},
    warn: function () { __host.log("warn: " + Array.prototype.join.call(arguments, " ")); },
    error: function () { __host.log("error: " + Array.prototype.join.call(arguments, " ")); },
    clear: function () {},
};

// Timers ride on a host coroutine delay, so they fire while the engine
// waits for promises.
var __timers = { next: 1, live: {} };
function setTimeout(fn, ms) {
    var args = Array.prototype.slice.call(arguments, 2);
    var id = __timers.next++;
    __timers.live[id] = true;
    __delay(Math.max(0, ms | 0)).then(function () {
        if (!__timers.live[id]) return;
        delete __timers.live[id];
        fn.apply(null, args);
    });
    return id;
}
function clearTimeout(id) { delete __timers.live[id]; }
function setInterval(fn, ms) {
    var args = Array.prototype.slice.call(arguments, 2);
    var id = __timers.next++;
    __timers.live[id] = true;
    (function tick() {
        __delay(Math.max(1, ms | 0)).then(function () {
            if (!__timers.live[id]) return;
            fn.apply(null, args);
            tick();
        });
    })();
    return id;
}
function clearInterval(id) { delete __timers.live[id]; }

var __packages = ["Bridge", "Http", "Utilities", "DOMParser"];
var bridge = {
    // At least the build the plugins treat as current (older ones get a legacy path).
    buildVersion: 300,
    buildFlavor: "stable",
    buildSpecVersion: 2,
    buildPlatform: "android",
    captchaUserAgent: "",
    authUserAgent: "",
    // No "UMPSource": that path needs a browser for attestation.
    supportedFeatures: ["ReloadRequiredException", "HttpBatchClient", "Async"],
    supportedContent: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    hasPackage: function (name) { return __packages.indexOf(name) >= 0; },
    dispose: function () {},
    setTimeout: setTimeout,
    clearTimeout: clearTimeout,
    sleep: function (ms) { __host.sleep(ms | 0); },
    toast: function (text) { __host.log("toast: " + text); },
    log: function (text) { __host.log(String(text)); },
    devSubmit: function () {},
    throwTest: function (text) { throw new Error(text); },
    isLoggedIn: function () { return false; },
    getHardwareCodecs: function () { return []; },
};

function __newBatch(requests) {
    function add(r) { requests.push(r); return __newBatch(requests); }
    return {
        GET: function (url, headers) { return add({ method: "GET", url: url, headers: headers || {} }); },
        POST: function (url, body, headers) { return add(Object.assign({ method: "POST", url: url, headers: headers || {} }, __body(body))); },
        request: function (method, url, headers) { return add({ method: method, url: url, headers: headers || {} }); },
        requestWithBody: function (method, url, body, headers) { return add(Object.assign({ method: method, url: url, headers: headers || {} }, __body(body))); },
        DUMMY: function () { return add(null); },
        clientGET: function (id, url, headers) { return this.GET(url, headers); },
        clientPOST: function (id, url, body, headers) { return this.POST(url, body, headers); },
        clientRequest: function (id, method, url, headers) { return this.request(method, url, headers); },
        clientRequestWithBody: function (id, method, url, body, headers) { return this.requestWithBody(method, url, body, headers); },
        execute: function () {
            var results = JSON.parse(__host.batch(JSON.stringify(requests)));
            return results.map(function (r) {
                if (r == null) return null;
                if (r.error) throw new Error(r.error);
                return r;
            });
        },
    };
}

function __client() {
    var self = {
        clientId: "default",
        GET: function (url, headers, useAuth, bytes) { return http.GET(url, headers, useAuth, bytes); },
        POST: function (url, body, headers, useAuth, bytes) { return http.POST(url, body, headers, useAuth, bytes); },
        request: function (method, url, headers, useAuth, bytes) { return http.request(method, url, headers, useAuth, bytes); },
        requestWithBody: function (method, url, body, headers, useAuth, bytes) { return http.requestWithBody(method, url, body, headers, useAuth, bytes); },
        setDefaultHeaders: function () {},
        setDoApplyCookies: function () {},
        setDoUpdateCookies: function () {},
        setDoAllowNewCookies: function () {},
        setTimeout: function () {},
        resetAuthCookies: function () {},
        clearOtherCookies: function () {},
    };
    return self;
}

var http = {
    request: function (method, url, headers, useAuth, bytes) {
        return __response(__host.request(method, url, JSON.stringify(headers || {}), null, false, !!bytes), bytes);
    },
    requestWithBody: function (method, url, body, headers, useAuth, bytes) {
        var b = __body(body);
        return __response(__host.request(method, url, JSON.stringify(headers || {}), b.body, b.bodyIsBytes, !!bytes), bytes);
    },
    GET: function (url, headers, useAuth, bytes) { return http.request("GET", url, headers, useAuth, bytes); },
    POST: function (url, body, headers, useAuth, bytes) { return http.requestWithBody("POST", url, body, headers, useAuth, bytes); },
    batch: function () { return __newBatch([]); },
    newClient: function () { return __client(); },
    getDefaultClient: function () { return __client(); },
};

var utility = {
    toBase64: function (bytes) { return __bytesToB64(bytes); },
    fromBase64: function (text) { return __b64ToBytes(text); },
    md5: function (bytes) { return __b64ToBytes(__host.hash("md5", __bytesToB64(bytes))); },
    md5String: function (text) { return __host.hashString("md5", String(text)); },
    sha256: function (bytes) { return __b64ToBytes(__host.hash("sha256", __bytesToB64(bytes))); },
    sha256String: function (text) { return __host.hashString("sha256", String(text)); },
    randomUUID: function () { return __host.uuid(); },
};

var domParser = {
    parseFromString: function () { throw new Error("DOMParser isn't available in Glorp"); },
};
