import path from "node:path";
import { fileURLToPath } from "node:url";

const siteRoot = path.dirname(fileURLToPath(import.meta.url));

/** @type {import('next').NextConfig} */
const nextConfig = {
  turbopack: { root: siteRoot },
  async redirects() {
    return [{ source: "/", destination: "/report.html", permanent: false }];
  }
};

export default nextConfig;
