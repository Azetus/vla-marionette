#!/usr/bin/env node
/**
 * Build a Java argv for a NeoForge client from the installed version JSON.
 *
 * The version JSON is self-contained for this installation (no inheritsFrom):
 * it lists the client jar, all libraries and the main class. Rules are filtered
 * for Windows/x86_64. Paths may contain spaces; the caller must pass the
 * resulting array element-by-element.
 */

import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const versionDir = path.resolve(arg("version-dir", ""));
if (!arg("version-dir")) {
  console.error("[mc-argv] --version-dir is required");
  process.exit(2);
}

const versionId = path.basename(versionDir);
const versionJson = path.resolve(arg("version-json", path.join(versionDir, `${versionId}.json`)));
const root = path.resolve(versionDir, "..", "..");
const librariesDir = path.resolve(arg("libraries-dir", path.join(root, "libraries")));
const assetsDir = path.resolve(arg("assets-dir", path.join(root, "assets")));
const nativesDir = path.resolve(arg("natives-dir", path.join(versionDir, `${versionId}-natives`)));
const gameDir = path.resolve(arg("game-dir", path.join(process.cwd(), "run", "client")));
const world = arg("world", "vla-m1");
const port = Number(arg("port", "25580"));
const runId = arg("run-id", `vla-${Date.now()}`);

function fail(message) {
  console.error(`[mc-argv] ${message}`);
  process.exit(2);
}

if (!fs.existsSync(versionJson)) fail(`version JSON not found: ${versionJson}`);
if (!fs.existsSync(librariesDir)) fail(`libraries dir not found: ${librariesDir}`);
if (!fs.existsSync(assetsDir)) fail(`assets dir not found: ${assetsDir}`);
if (!fs.existsSync(nativesDir)) fail(`natives dir not found: ${nativesDir}`);

const version = JSON.parse(fs.readFileSync(versionJson, "utf8"));
const platform = process.platform === "win32" ? "windows" : process.platform === "darwin" ? "osx" : "linux";
const arch = process.arch === "x64" ? "x86_64" : process.arch === "ia32" ? "x86" : process.arch;
const features = {
  has_custom_resolution: true,
  has_quick_plays_support: true,
  is_quick_play_singleplayer: true,
};

function matchesRule(rule) {
  if (rule.os) {
    if (rule.os.name && rule.os.name !== platform) return false;
    if (rule.os.arch && rule.os.arch !== arch) return false;
  }
  if (rule.features) {
    for (const [key, value] of Object.entries(rule.features)) {
      if (Boolean(features[key]) !== Boolean(value)) return false;
    }
  }
  return true;
}

function rulesAllow(rules) {
  if (!rules || rules.length === 0) return true;
  let allowed = false;
  for (const rule of rules) {
    if (matchesRule(rule)) allowed = rule.action === "allow";
  }
  return allowed;
}

const versionJar = path.join(versionDir, `${versionId}.jar`);
if (!fs.existsSync(versionJar)) fail(`client/version jar not found: ${versionJar}`);

const classpath = [versionJar];
for (const library of version.libraries ?? []) {
  if (!rulesAllow(library.rules)) continue;
  const artifact = library.downloads?.artifact;
  if (!artifact?.path) continue;
  const file = path.join(librariesDir, ...artifact.path.split("/"));
  if (!fs.existsSync(file)) fail(`missing library: ${file}`);
  classpath.push(file);
}

if (!fs.existsSync(path.join(nativesDir, "glfw.dll"))) {
  fail(`glfw.dll not found under natives dir: ${nativesDir}`);
}

const sep = platform === "windows" ? ";" : ":";
const classpathString = classpath.join(sep);
const assetIndex = String(version.assets ?? version.assetIndex?.id ?? "30");

const jvm = [
  "-Xms1G",
  "-Xmx4G",
  "-XX:+UseG1GC",
  `-Djava.library.path=${nativesDir}`,
  `-Djna.tmpdir=${nativesDir}`,
  `-Dorg.lwjgl.system.SharedLibraryExtractPath=${nativesDir}`,
  `-Dio.netty.native.workdir=${nativesDir}`,
  "-Dminecraft.launcher.brand=VLA-Marionette",
  "-Dminecraft.launcher.version=0.1.0",
  `-DlibraryDirectory=${librariesDir}`,
  "-Djava.net.preferIPv6Addresses=system",
  "-Dvla.bridge.host=127.0.0.1",
  `-Dvla.bridge.port=${port}`,
  `-Dvla.run.id=${runId}`,
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED",
  "--add-opens",
  "java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-exports",
  "jdk.naming.dns/com.sun.jndi.dns=java.naming",
  "-cp",
  classpathString,
];

const game = [
  "--username", "VLA",
  "--version", versionId,
  "--gameDir", gameDir,
  "--assetsDir", assetsDir,
  "--assetIndex", assetIndex,
  "--uuid", "00000000000000000000000000000000",
  "--accessToken", "0",
  "--clientId", "0",
  "--xuid", "0",
  "--versionType", "release",
  "--width", "640",
  "--height", "360",
  "--quickPlaySingleplayer", world,
  "--fml.neoForgeVersion", "26.1.2.126",
  "--fml.mcVersion", "26.1.2",
  "--fml.neoFormVersion", "1",
];

const result = {
  versionId,
  mainClass: version.mainClass,
  jvmArgs: jvm,
  gameArgs: game,
  args: [...jvm, version.mainClass, ...game],
  classpath,
  libraries: classpath.length - 1,
  nativesDir,
  librariesDir,
  assetsDir,
  assetIndex,
  gameDir,
  world,
  port,
  runId,
};

const output = arg("out", "");
if (output) {
  fs.mkdirSync(path.dirname(path.resolve(output)), { recursive: true });
  fs.writeFileSync(path.resolve(output), JSON.stringify(result, null, 2));
  console.log(`[mc-argv] wrote ${output}: ${result.libraries} libraries, main=${result.mainClass}`);
} else {
  console.log(JSON.stringify(result, null, 2));
}
