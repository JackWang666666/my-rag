/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Emit .next/standalone with only the traced runtime dependencies, so the
  // Docker image can be a slim `node server.js` instead of shipping node_modules.
  output: "standalone",
};

module.exports = nextConfig;
