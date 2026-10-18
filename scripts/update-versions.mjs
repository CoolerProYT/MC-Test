// Keeps the MCTest versions shown in the docs in sync with what is published on the Maven repository.
//
// - Finds every published mod artifact (mctest-<loader>-<minecraft version>) through the repository's
//   Reposilite API, so new Minecraft versions and loaders (backports) show up without changing this script.
// - Rewrites the version table between <!-- mctest-versions:start --> and <!-- mctest-versions:end -->.
// - Rewrites the version in dependency snippets such as
//   localRuntime("com.coolerpromc.mctest:mctest-fabric-${minecraft_version}:26.3.0.2") to the latest release for
//   this branch's minecraft_version (gradle.properties). Snippets with a literal Minecraft version use that one.
//
// Usage: node scripts/update-versions.mjs [--check]
//   --check  change nothing; exit 1 if a file is out of date (for CI)
// Node 22+, no dependencies. Files are read and written byte for byte (latin1), so stray non-UTF-8 bytes survive.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const REPO = 'https://maven.coolerpromc.com';
const REPO_NAME = 'releases';
const GROUP_PATH = 'com/coolerpromc/mctest';
const FILES = [
  'README.md',
  'mcp/README.md',
  'docs/guide/getting-started.md',
  'skill/minecraft-mod-tester/references/setup.md',
];
const LOADERS = ['fabric', 'neoforge', 'forge'];
const LOADER_NAMES = { fabric: 'Fabric', neoforge: 'NeoForge', forge: 'Forge' };
const check = process.argv.includes('--check');

async function get(url, accept) {
  const res = await fetch(url, { headers: accept ? { Accept: accept } : {} });
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return res.text();
}

// Numeric-aware comparison: 1.21.10 > 1.21.9, 26.3.0.10 > 26.3.0.9.
function compareVersions(a, b) {
  const pa = a.split(/[.+-]/), pb = b.split(/[.+-]/);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? '', y = pb[i] ?? '';
    const nx = Number(x), ny = Number(y);
    const d = Number.isNaN(nx) || Number.isNaN(ny) ? x.localeCompare(y) : nx - ny;
    if (d) return d;
  }
  return 0;
}

/** { "<mc version>": { fabric: "26.3.0.3", neoforge: "26.3.0.3" } } */
async function publishedVersions() {
  const listing = JSON.parse(await get(`${REPO}/api/maven/details/${REPO_NAME}/${GROUP_PATH}`, 'application/json'));
  const result = {};
  for (const { name, type } of listing.files ?? []) {
    const m = type === 'DIRECTORY' && new RegExp(`^mctest-(${LOADERS.join('|')})-(.+)$`).exec(name);
    if (!m) continue; // mctest-common-* is not something users depend on
    const [, loader, mc] = m;
    const xml = await get(`${REPO}/${REPO_NAME}/${GROUP_PATH}/${name}/maven-metadata.xml`);
    const release = /<release>([^<]+)<\/release>/.exec(xml)?.[1] ?? /<latest>([^<]+)<\/latest>/.exec(xml)?.[1];
    if (release) (result[mc] ??= {})[loader] = release;
  }
  if (!Object.keys(result).length) throw new Error('No mctest artifacts found on the repository');
  return result;
}

function table(versions) {
  const loaders = LOADERS.filter((l) => Object.values(versions).some((v) => v[l]));
  const mcs = Object.keys(versions).sort((a, b) => compareVersions(b, a));
  const lines = [
    `| Minecraft | ${loaders.map((l) => LOADER_NAMES[l]).join(' | ')} |`,
    `|---|${loaders.map(() => '---').join('|')}|`,
    ...mcs.map((mc) => `| ${mc} | ${loaders.map((l) => (versions[mc][l] ? '`' + versions[mc][l] + '`' : '-')).join(' | ')} |`),
  ];
  return lines.join('\n');
}

const versions = await publishedVersions();
// The Minecraft version in this branch's artifact names (artifact_minecraft_version, else minecraft_version).
const props = readFileSync(join(root, 'gradle.properties'), 'utf8');
const branchMc = (/^artifact_minecraft_version=(.+)$/m.exec(props) ?? /^minecraft_version=(.+)$/m.exec(props))?.[1]?.trim();

const SNIPPET = new RegExp(`(mctest-(${LOADERS.join('|')})-(\\$\\{minecraft_version\\}|[0-9][\\w.]*):)([0-9][\\w.+-]*)`, 'g');
const TABLE = /(<!-- mctest-versions:start -->)[\s\S]*?(<!-- mctest-versions:end -->)/g;

let stale = [];
for (const file of FILES) {
  const path = join(root, file);
  let before;
  try {
    before = readFileSync(path, 'latin1');
  } catch {
    continue; // not every branch has every file
  }
  const after = before
    .replace(TABLE, (_, start, end) => [start, table(versions), end].join('\n'))
    .replace(SNIPPET, (whole, prefix, loader, mc, version) => {
      const latest = versions[mc === '${minecraft_version}' ? branchMc : mc]?.[loader];
      return latest ? prefix + latest : whole;
    });
  if (after !== before) {
    stale.push(file);
    if (!check) writeFileSync(path, after, 'latin1');
  }
}

console.log(`Published: ${JSON.stringify(versions)} (this branch: Minecraft ${branchMc})`);
if (!stale.length) console.log('All files are up to date.');
else if (check) {
  console.log(`Out of date: ${stale.join(', ')}. Run node scripts/update-versions.mjs`);
  process.exitCode = 1; // not process.exit(): it can abort Node on Windows while fetch sockets close
} else console.log(`Updated: ${stale.join(', ')}`);
