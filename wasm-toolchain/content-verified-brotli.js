"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const zlib = require("node:zlib");

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function readText(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, "utf8").trim() : "";
}

function atomicWrite(file, bytes) {
  const temp = `${file}.tmp-${process.pid}-${crypto.randomBytes(4).toString("hex")}`;
  try {
    fs.writeFileSync(temp, bytes);
    fs.renameSync(temp, file);
  } finally {
    fs.rmSync(temp, {force: true});
  }
}

function cachePaths(rawPath) {
  const brotliPath = `${rawPath}.br`;
  return {
    brotliPath,
    checksumPath: `${brotliPath}.sha256`,
    metadataPath: `${brotliPath}.meta`,
  };
}

function readVerifiedBrotli(rawPath, rawBytes, requestedQuality, {allowHigherQuality = false} = {}) {
  const paths = cachePaths(rawPath);
  if (!fs.existsSync(paths.brotliPath)) return null;
  const qualityMatch = /^quality=([0-9]|10|11)$/.exec(readText(paths.metadataPath));
  if (!qualityMatch) return null;
  const actualQuality = Number(qualityMatch[1]);
  if (actualQuality !== requestedQuality && !(allowHigherQuality && actualQuality > requestedQuality)) {
    return null;
  }

  const compressed = fs.readFileSync(paths.brotliPath);
  const checksum = readText(paths.checksumPath);
  const expectedChecksum = `${sha256(rawBytes)} ${sha256(compressed)}`;
  if (checksum !== expectedChecksum) return null;
  try {
    if (!zlib.brotliDecompressSync(compressed).equals(rawBytes)) return null;
  } catch {
    return null;
  }
  return {compressed, quality: actualQuality, paths};
}

function persistVerifiedBrotli(rawPath, rawBytes, compressed, quality) {
  if (!Number.isInteger(quality) || quality < 0 || quality > 11) {
    throw new Error("Brotli quality must be an integer from 0 through 11");
  }
  if (!zlib.brotliDecompressSync(compressed).equals(rawBytes)) {
    throw new Error("Refusing to persist Brotli bytes that do not decode to the source payload");
  }
  const paths = cachePaths(rawPath);
  atomicWrite(paths.brotliPath, compressed);
  atomicWrite(paths.checksumPath, `${sha256(rawBytes)} ${sha256(compressed)}\n`);
  atomicWrite(paths.metadataPath, `quality=${quality}\n`);
  return paths;
}

module.exports = {
  atomicWrite,
  cachePaths,
  persistVerifiedBrotli,
  readText,
  readVerifiedBrotli,
  sha256,
};
