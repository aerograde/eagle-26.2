#!/usr/bin/env node
"use strict";

// Convert the already-compressed music EPK into a normal Minecraft resource
// pack. OGG data is stored verbatim in the ZIP: recompressing it would cost build
// time without producing a meaningfully smaller file.

const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

const repo = path.resolve(__dirname, "..");
const input = path.resolve(repo, process.argv[2] || "target_teavm/build/web/music.epk");
const output = path.resolve(repo, process.argv[3] || "eaglercraft-26.2-optional-music.zip");

function fail(message) {
  console.error(`[music-pack] ERROR: ${message}`);
  process.exit(1);
}

function parseEPK(bytes) {
  let offset = 0;
  const byte = () => bytes[offset++];
  const short = () => byte() * 0x100 + byte();
  const integer = () => (byte() * 0x1000000 + (byte() << 16) + (byte() << 8) + byte()) >>> 0;
  const ascii = () => {
    const length = byte();
    const value = bytes.subarray(offset, offset + length).toString("latin1");
    offset += length;
    return value;
  };

  if (bytes.subarray(0, 8).toString("latin1") !== "EAGPKG$$") fail("input is not an EPK v2 archive");
  offset = 8;
  if (!ascii().startsWith("ver2.")) fail("unsupported EPK version");
  let length = byte();
  offset += length; // archive name
  length = short();
  offset += length; // archive comment
  offset += 8; // timestamp
  const objectCount = integer();
  const compression = String.fromCharCode(byte());
  let stream = bytes.subarray(offset, bytes.length - 8);
  if (compression === "G") stream = zlib.gunzipSync(stream);
  else if (compression === "Z") stream = zlib.inflateSync(stream);
  else if (compression !== "0") fail(`unsupported EPK compression '${compression}'`);

  offset = 0;
  const streamByte = () => stream[offset++];
  const streamInteger = () => (streamByte() * 0x1000000 + (streamByte() << 16) + (streamByte() << 8) + streamByte()) >>> 0;
  const streamAscii = () => {
    const nameLength = streamByte();
    const value = stream.subarray(offset, offset + nameLength).toString("latin1");
    offset += nameLength;
    return value;
  };
  const files = [];
  for (let i = 0; i < objectCount; ++i) {
    const type = stream.subarray(offset, offset + 4).toString("latin1");
    offset += 4;
    const name = streamAscii();
    const objectLength = streamInteger();
    if (type === "FILE") {
      if (objectLength < 5) fail(`invalid EPK entry '${name}'`);
      offset += 4; // EPK CRC (the runtime already validated this archive)
      const data = stream.subarray(offset, offset + objectLength - 5);
      offset += objectLength - 5;
      if (streamByte() !== 58) fail(`invalid EPK file terminator for '${name}'`);
      files.push({ name, data });
    } else {
      offset += objectLength;
    }
    if (streamByte() !== 62) fail(`invalid EPK object terminator for '${name}'`);
  }
  return files;
}

const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; ++n) {
  let value = n;
  for (let k = 0; k < 8; ++k) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  crcTable[n] = value >>> 0;
}
function crc32(data) {
  let value = 0xffffffff;
  for (let i = 0; i < data.length; ++i) value = crcTable[(value ^ data[i]) & 255] ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
}

function makeStoredZip(files) {
  const localParts = [];
  const centralParts = [];
  let localOffset = 0;
  for (const file of files) {
    const name = Buffer.from(file.name.replaceAll("\\", "/"), "utf8");
    const data = Buffer.from(file.data);
    const checksum = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(0, 8); // STORE
    local.writeUInt16LE(0, 10); // deterministic 1980-01-01 timestamp
    local.writeUInt16LE(0x0021, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    localParts.push(local, name, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x0021, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(localOffset, 42);
    centralParts.push(central, name);
    localOffset += local.length + name.length + data.length;
  }
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(localOffset, 16);
  return Buffer.concat([...localParts, ...centralParts, end]);
}

const musicFiles = parseEPK(fs.readFileSync(input)).filter(file =>
  file.name.startsWith("assets/minecraft/sounds/music/") ||
  file.name.startsWith("assets/minecraft/sounds/records/")
);
if (musicFiles.length === 0) fail("music EPK did not contain music or record files");

const metadata = Buffer.from(JSON.stringify({
  pack: {
    min_format: [88, 0],
    max_format: [88, 0],
    description: "Optional Eaglercraft 26.2 background music and music discs"
  }
}, null, 2) + "\n");
const icon = fs.readFileSync(path.join(repo, "game", "src", "main", "resources", "pack.png"));
const zip = makeStoredZip([
  { name: "pack.mcmeta", data: metadata },
  { name: "pack.png", data: icon },
  ...musicFiles
]);
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, zip);
console.log(`[music-pack] wrote ${output}`);
console.log(`[music-pack] ${musicFiles.length} unchanged OGG files, ${zip.length.toLocaleString()} bytes`);
