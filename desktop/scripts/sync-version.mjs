import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const version = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8")).version;
for (const [name, update] of [
  ["package-lock.json", text => {
    const lock = JSON.parse(text);
    lock.version = version;
    lock.packages[""].version = version;
    return JSON.stringify(lock, null, 2) + "\n";
  }],
  ...(fs.existsSync(path.join(root, "src-tauri/Cargo.lock")) ? [["src-tauri/Cargo.lock", text =>
    text.replace(/(name = "dot-desktop"\r?\nversion = ")[^"]+"/, `$1${version}"`)]] : []),
  ["src-tauri/Cargo.toml", text => text.replace(/^version = ".*"/m, `version = "${version}"`)],
  ["src-tauri/tauri.conf.json", text => JSON.stringify({ ...JSON.parse(text), version }, null, 2) + "\n"],
]) {
  const file = path.join(root, name), before = fs.readFileSync(file, "utf8"), after = update(before);
  if (process.argv.includes("--check")) {
    if (before.replace(/\r\n/g, "\n") !== after.replace(/\r\n/g, "\n")) throw new Error(`${name}: run npm run sync-version`);
  } else fs.writeFileSync(file, after);
}
