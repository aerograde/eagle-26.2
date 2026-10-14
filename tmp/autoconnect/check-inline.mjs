// Mimics the two inlining steps build-single-html.js performs for the modules
// this change adds, against the real deployed shell. Catches a missing or
// mistyped script tag without paying for a full single-file package.
import { readFileSync } from "node:fs";

const web = new URL("../../target_teavm_wasm_gc/build/web/", import.meta.url);
let html = readFileSync(new URL("index.html", web), "utf8");
const auto = readFileSync(new URL("autoconnect.js", web), "utf8");
const before = html.length;
html = html.replace(
  /<script type="text\/javascript" src="autoconnect\.js(?:\?v=[^"]+)?"><\/script>/,
  `<script type="text/javascript">\n${auto}\n</script>`
);
const inlined = html.length !== before;
const present = html.indexOf("__eaglerAC") >= 0;
const survived = html.indexOf('src="autoconnect.js') < 0;
console.log("autoconnect tag inlined:", inlined);
console.log("__eaglerAC present in the shell:", present);
console.log("no external script tag left:", survived);
if (!inlined || !present || !survived) process.exit(1);
