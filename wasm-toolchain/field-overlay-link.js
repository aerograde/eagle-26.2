"use strict";

// Substitute an already-built game JAR with an independently verified,
// transformed link-input JAR. This runs after Gradle and before TeaVM sees the
// program classpath; it never changes Gradle outputs or generated Wasm.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const REPO = path.resolve(__dirname, "..");
const APPLY_SCRIPT = path.join(REPO, "mod-support", "compiler", "apply-native-field-overlay.mjs");

function sha256File(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function substituteGameJarWithOverlay({ programClasspath, overlayJar, applyOverlay }) {
  if (!Array.isArray(programClasspath) || programClasspath.length === 0) {
    throw new Error("FIELD_OVERLAY_CLASSPATH_EMPTY");
  }
  let overlayStat = null;
  if (typeof overlayJar === "string" && path.isAbsolute(overlayJar)) {
    try { overlayStat = fs.statSync(overlayJar); } catch { overlayStat = null; }
  }
  if (!overlayStat || !overlayStat.isFile()) throw new Error("FIELD_OVERLAY_JAR_INVALID");
  const expectedSuffix = path.join("game", "build", "libs", "game.jar");
  const matches = programClasspath.filter(entry => entry.endsWith(expectedSuffix));
  if (matches.length !== 1) throw new Error(`FIELD_OVERLAY_GAME_JAR_AMBIGUOUS: ${matches.length}`);
  const gameJar = matches[0];
  const gameInputSHA256 = sha256File(gameJar);
  const overlaySHA256 = sha256File(overlayJar);
  const destination = path.join(REPO, "wasm-toolchain", "generated", "native-field-overlay",
      `${gameInputSHA256}-${overlaySHA256}`, "game.jar");
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  // This path is derived from both input hashes. Remove only this generated
  // derived artifact before regenerating it under the linker operation lock.
  fs.rmSync(destination, { force: true });
  const invoke = applyOverlay || (({ gameJar, overlayJar, output }) => {
    const result = spawnSync(process.execPath, [APPLY_SCRIPT, gameJar, overlayJar, output], {
      encoding: "utf8",
    });
    if (result.status !== 0) {
      throw new Error(`FIELD_OVERLAY_APPLY_FAILED: ${(result.stderr || result.stdout || "").trim()}`);
    }
    try {
      return JSON.parse(result.stdout);
    } catch (error) {
      throw new Error(`FIELD_OVERLAY_RECEIPT_UNREADABLE: ${error.message}`);
    }
  });
  const applied = invoke({ gameJar, overlayJar, output: destination });
  if (applied && applied.output) {
    if (applied.output !== destination || applied.sha256 !== sha256File(destination)) {
      throw new Error("FIELD_OVERLAY_RESULT_INVALID");
    }
  } else if (typeof applied !== "object" || applied === null) {
    throw new Error("FIELD_OVERLAY_RESULT_INVALID");
  }
  const nextClasspath = [...programClasspath];
  nextClasspath[programClasspath.indexOf(gameJar)] = destination;
  return { programClasspath: nextClasspath, gameJar, destination, gameInputSHA256, overlaySHA256, applied };
}

module.exports = { substituteGameJarWithOverlay, sha256File };
