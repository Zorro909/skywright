import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";

const report = await readFile(new URL("../public/report.html", import.meta.url), "utf8");
const worker = `const REPORT = ${JSON.stringify(report)};

export default {
  async fetch() {
    return new Response(REPORT, {
      headers: {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "public, max-age=300",
        "x-content-type-options": "nosniff",
        "referrer-policy": "strict-origin-when-cross-origin"
      }
    });
  }
};
`;

await mkdir(new URL("../dist/server/", import.meta.url), { recursive: true });
await mkdir(new URL("../dist/.openai/", import.meta.url), { recursive: true });
await writeFile(new URL("../dist/server/index.js", import.meta.url), worker);
await copyFile(
  new URL("../.openai/hosting.json", import.meta.url),
  new URL("../dist/.openai/hosting.json", import.meta.url)
);
