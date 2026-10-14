// Reads tmp/autoconnect/final-run*.log (two runs of the frozen revision) and
// reports, per run and per peer: stage timings, the sender's pulse supply
// (fired/dropped), the receiver's per-cell occupancy, and how many bits of the
// closest candidate read differ from what the other peer actually transmitted.
// Usage: node tmp/autoconnect/analyse-frozen.js
"use strict";

var fs = require("fs");

var LINE = /^\[(alice|bob)\] \[autoconnect \+(\d+)ms\] ([a-z]+): (.*)$/;

var STAGES = [
  ["certificate", /^certificate /],
  ["listeners ready", /^listener \d+ \(4\/4\)/],
  ["scanners ready", /^72 scanner connections ready/],
  ["sweep starts", /^sweeping /],
  ["peer found", /^peer found at /],
  ["enumerate done", /^aiming at \d+ of the peer's ports/],
  ["pulse pool ready", /^pulse pool ready/],
  ["attempt 1 sent", /^sending 304 bits/],
  ["attempt 1 done", /^attempt 1 done/],
  ["attempt 2 done", /^attempt 2 done/],
  ["attempt 3 done", /^attempt 3 done/],
  ["payload verified", /payload verified by checksum/],
  ["data channel open", /^data channel open/],
  ["connected", /^connected over a direct data channel/]
];

function parse(file) {
  var peers = { alice: { events: [], truths: [], reads: [], attempts: [] },
                bob: { events: [], truths: [], reads: [], attempts: [] } };
  var text = fs.readFileSync(file, "utf8");
  var lines = text.split(/\r?\n/);
  for (var i = 0; i < lines.length; i++) {
    var m = LINE.exec(lines[i]);
    if (!m) continue;
    var peer = m[1], at = Number(m[2]), phase = m[3], msg = m[4];
    var P = peers[peer];
    P.events.push({ at: at, phase: phase, msg: msg });

    var t = /^payload truth ([0-9a-f ]+) \(crc (\d+)\)$/.exec(msg);
    if (t) {
      P.truths.push({ at: at, bytes: t[1].trim().split(/\s+/).map(function (h) { return parseInt(h, 16); }) });
    }
    var a = /^attempt (\d) done: (\d+) pulses fired(?:, (\d+) had no free connection)?/.exec(msg);
    if (a) P.attempts.push({ at: at, attempt: Number(a[1]), fired: Number(a[2]), dropped: a[3] ? Number(a[3]) : 0 });
    var c = /^closest start: (\d) channels \(([^)]*)\), grid slots ([^,]*), [^,]*starts tried, cells (\d+)\/(\d+) filled \(([^)]*)\) over (\d+) copies, read bytes ([0-9a-f ]+?) \(want magic/.exec(msg);
    if (c) {
      // The bound sockets are listed socket:channel:arrivals, and the occupancy
      // list that follows is in the same socket order, so the two can be paired.
      var bound = [];
      var bre = /(\d+)=c(\d+):(\d+)/g, bm;
      while ((bm = bre.exec(c[2])) !== null) {
        bound.push({ socket: Number(bm[1]), channel: Number(bm[2]), arrivals: Number(bm[3]) });
      }
      // The occupancy list is indexed by channel index (extractBits fills
      // perChannel[channel]), not by socket order.
      var perChannel = [];
      var pre = /(\d+) of (\d+)/g, pm;
      while ((pm = pre.exec(c[6])) !== null) perChannel.push({ filled: Number(pm[1]), of: Number(pm[2]) });
      P.reads.push({
        at: at, channels: Number(c[1]),
        bound: bound, grid: c[3],
        filled: Number(c[4]), total: Number(c[5]),
        perChannel: perChannel, copies: Number(c[7]),
        bytes: c[8].trim().split(/\s+/).map(function (h) { return parseInt(h, 16); })
      });
    }
  }
  return peers;
}

function stageTimes(P, name) {
  var re = null;
  for (var i = 0; i < STAGES.length; i++) if (STAGES[i][0] === name) re = STAGES[i][1];
  var out = [];
  for (var j = 0; j < P.events.length; j++) {
    if (re && re.test(P.events[j].msg)) out.push(P.events[j].at);
  }
  return out;
}

function bitsOf(bytes) {
  var out = [];
  for (var i = 0; i < bytes.length; i++) {
    for (var b = 7; b >= 0; b--) out.push((bytes[i] >> b) & 1);
  }
  return out;
}

function nibblesOf(bytes, count) {
  var bits = bitsOf(bytes);
  var out = [];
  for (var i = 0; i + 4 <= bits.length && out.length < count; i += 4) {
    out.push((bits[i] << 3) | (bits[i + 1] << 2) | (bits[i + 2] << 1) | bits[i + 3]);
  }
  return out;
}

function compare(truth, read) {
  var n = Math.min(truth.length, read.length);
  var bitErr = 0;
  for (var i = 0; i < n; i++) {
    for (var b = 7; b >= 0; b--) {
      if (((truth[i] >> b) & 1) !== ((read[i] >> b) & 1)) bitErr++;
    }
  }
  bitErr += (truth.length - n + read.length - n) * 8;
  var tn = nibblesOf(truth, 76), rn = nibblesOf(read, 76);
  var nibErr = 0;
  for (var j = 0; j < 76; j++) if (tn[j] !== rn[j]) nibErr++;
  // A channel whose bound socket is the wrong one would permute the four
  // channels of every slot; if some permutation of the read channels makes the
  // payload agree, the carrier is readable and only the labelling is wrong.
  var perms = [[0, 1, 2, 3], [0, 1, 3, 2], [0, 2, 1, 3], [0, 2, 3, 1], [0, 3, 1, 2], [0, 3, 2, 1],
    [1, 0, 2, 3], [1, 0, 3, 2], [1, 2, 0, 3], [1, 2, 3, 0], [1, 3, 0, 2], [1, 3, 2, 0],
    [2, 0, 1, 3], [2, 0, 3, 1], [2, 1, 0, 3], [2, 1, 3, 0], [2, 3, 0, 1], [2, 3, 1, 0],
    [3, 0, 1, 2], [3, 0, 2, 1], [3, 1, 0, 2], [3, 1, 2, 0], [3, 2, 0, 1], [3, 2, 1, 0]];
  var bestPerm = null;
  for (var p = 0; p < perms.length; p++) {
    var err = 0;
    for (var slot = 0; slot < 19; slot++) {
      for (var ch = 0; ch < 4; ch++) {
        if (tn[slot * 4 + perms[p][ch]] !== rn[slot * 4 + ch]) err++;
      }
    }
    if (!bestPerm || err < bestPerm.err) bestPerm = { perm: perms[p], err: err };
  }
  return { bitErr: bitErr, bites: truth.length * 8, nibErr: nibErr, perm: bestPerm };
}

function report(file) {
  var peers = parse(file);
  console.log("\n################ " + file);
  ["alice", "bob"].forEach(function (name) {
    var P = peers[name];
    console.log("\n-- " + name);
    var row = [];
    for (var s = 0; s < STAGES.length; s++) {
      var ts = stageTimes(P, STAGES[s][0]);
      row.push(STAGES[s][0] + (ts.length ? " +" + ts[0] + "ms" + (ts.length > 1 ? " (x" + ts.length + ")" : "") : " -"));
    }
    console.log("   stages: " + row.join(" | "));
    console.log("   supply: " + (P.attempts.length ? P.attempts.map(function (a) {
      return "a" + a.attempt + " +" + a.at + "ms " + a.fired + " fired/" + a.dropped + " dropped";
    }).join(" ; ") : "no payload attempt"));
    var best = null;
    for (var r = 0; r < P.reads.length; r++) if (!best || P.reads[r].filled > best.filled) best = P.reads[r];
    if (best) {
      console.log("   best read: +" + best.at + "ms, " + best.channels + " channels bound, cells "
        + best.filled + "/" + best.total + " filled over " + best.copies + " copies, grid " + best.grid);
      var byChannel = {};
      best.bound.forEach(function (b) { byChannel[b.channel] = b; });
      console.log("              occupancy by channel: " + best.perChannel.map(function (pc, c) {
        var b = byChannel[c];
        return "c" + c + " " + pc.filled + "/" + pc.of + " cells on socket "
          + (b ? b.socket + " (" + b.arrivals + " arrivals)" : "unbound");
      }).join(" | "));
    } else {
      console.log("   best read: none (never reached 4 bound channels)");
    }
    var four = P.reads.filter(function (x) { return x.channels >= 4; });
    if (four.length) {
      var chStats = [];
      for (var ci = 0; ci < 4; ci++) {
        var vals = four.map(function (x) {
          return x.perChannel[ci] ? x.perChannel[ci].filled : null;
        }).filter(function (v) { return v !== null; });
        vals.sort(function (a, b) { return a - b; });
        chStats.push("c" + ci + " " + (vals.length ? vals[0] + "-" + vals[vals.length - 1]
          + " (median " + vals[Math.floor(vals.length / 2)] + ")" : "no data"));
      }
      console.log("   per-channel occupancy across the " + four.length
        + " four-channel candidates (of 19 cells): " + chStats.join(" | "));
    }
    var occ = P.reads.map(function (x) { return x.filled; });
    if (occ.length) {
      occ.sort(function (a, b) { return a - b; });
      var med = occ[Math.floor(occ.length / 2)];
      console.log("   occupancy over " + occ.length + " logged candidates: min " + occ[0]
        + ", median " + med + ", max " + occ[occ.length - 1] + " of 76 cells");
      console.log("   four-channel candidates: " + P.reads.filter(function (x) { return x.channels >= 4; }).length);
    }
  });
  // Bit errors: the receiver's read of the *other* peer's transmission, scored
  // against the payload that peer actually logged as sent.
  ["alice", "bob"].forEach(function (name) {
    var other = name === "alice" ? "bob" : "alice";
    if (!peers[name].reads.length || !peers[other].truths.length) return;
    console.log("\n-- " + name + " reading " + other + "'s payload:");
    peers[name].reads.forEach(function (read) {
      var truth = null;
      for (var i = 0; i < peers[other].truths.length; i++) {
        if (peers[other].truths[i].at <= read.at) truth = peers[other].truths[i];
      }
      if (!truth || read.at - truth.at > 30000) return;
      var cmp = compare(truth.bytes, read.bytes);
      console.log("   +" + read.at + "ms (vs " + other + "'s payload sent at +" + truth.at + "ms): "
        + cmp.bitErr + "/" + cmp.bites + " bits differ (" + (100 * cmp.bitErr / cmp.bites).toFixed(1)
        + "%), " + cmp.nibErr + "/76 nibbles differ, cells " + read.filled + "/76 filled (at least "
        + (76 - read.filled) + " of those nibbles are empty cells reading zero); best channel"
        + " permutation " + cmp.perm.perm.join("") + " leaves " + cmp.perm.err + "/76 nibbles wrong");
    });
  });
}

function summary(file) {
  var peers = parse(file);
  var best = null;
  ["alice", "bob"].forEach(function (name) {
    var other = name === "alice" ? "bob" : "alice";
    peers[name].reads.forEach(function (read) {
      var truth = null;
      for (var i = 0; i < peers[other].truths.length; i++) {
        if (peers[other].truths[i].at <= read.at) truth = peers[other].truths[i];
      }
      if (!truth || read.at - truth.at > 30000) return;
      var cmp = compare(truth.bytes, read.bytes);
      if (!best || cmp.nibErr < best.nibErr) {
        best = { nibErr: cmp.nibErr, bitErr: cmp.bitErr, filled: read.filled, at: read.at, who: name };
      }
    });
  });
  if (best) {
    console.log("   CLOSEST ANY CANDIDATE GOT: " + best.who + " at +" + best.at + "ms, cells "
      + best.filled + "/76 filled, " + best.nibErr + "/76 nibbles and " + best.bitErr
      + "/304 bits wrong against the transmitted payload");
  }
}

var files = ["tmp/autoconnect/final-run1.log", "tmp/autoconnect/final-run2.log"];
for (var i = 0; i < files.length; i++) {
  if (fs.existsSync(files[i])) { report(files[i]); summary(files[i]); }
}
