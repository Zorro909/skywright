import { readFile } from 'node:fs/promises';

const packageManifest = JSON.parse(
  await readFile(new URL('../package.json', import.meta.url), 'utf8'),
);
const requiredNode = packageManifest.engines.node;
const requiredPnpm = packageManifest.engines.pnpm;

const actualNode = process.versions.node;
const userAgent = process.env['npm_config_user_agent'] ?? '';
const actualPnpm = /^pnpm\/([^ ]+)/u.exec(userAgent)?.[1];

if (actualNode !== requiredNode || actualPnpm !== requiredPnpm) {
  console.error(
    `Skywright web requires Node ${requiredNode} and pnpm ${requiredPnpm}; received Node ${actualNode} and pnpm ${actualPnpm ?? 'unknown'}.`,
  );
  process.exit(1);
}
