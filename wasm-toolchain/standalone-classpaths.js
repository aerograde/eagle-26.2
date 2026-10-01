"use strict";

// Read both authoritative Gradle classpaths from the same invocation that
// compiles the input jars. Repeated Gradle JVM/project startup adds no linker
// information and used to occur three times for each client/mesh build.
function readStandaloneClasspaths(output) {
  const prefix = "EAGLER_STANDALONE_CLASSPATHS=";
  const records = output.split(/\r?\n/).filter(line => line.startsWith(prefix));
  if (records.length !== 1) throw new Error("Expected exactly one standalone classpath record");
  const value = JSON.parse(records[0].slice(prefix.length));
  for (const key of ["tool", "program"]) {
    if (!Array.isArray(value[key]) || value[key].length === 0
        || value[key].some(entry => typeof entry !== "string" || !require("node:path").isAbsolute(entry))) {
      throw new Error(`Invalid standalone ${key} classpath`);
    }
    // Gradle lists optional source/resource directories even when empty and
    // absent. Match the original standalone helpers' existence filtering.
    value[key] = value[key].filter(entry => require("node:fs").existsSync(entry));
    if (value[key].length === 0) throw new Error(`Empty standalone ${key} classpath`);
  }
  return value;
}

module.exports = { readStandaloneClasspaths };
