#!/usr/bin/env node
// Automated Scittle CDN test runner using Playwright.
// Serves only the test HTML locally — CEDN source loads from jsdelivr CDN.
//
// Usage: node test/run-scittle-cdn.mjs [ref]
//   ref defaults to "main" (what CI will publish next); pass a release tag
//   such as v1.5.2 to test what the README tells users to load.

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { join, extname } from "node:path";
import { chromium } from "playwright";

const TEST_DIR = new URL(".", import.meta.url).pathname;
const TIMEOUT_MS = 60_000;
const REF = process.argv[2] || "main";
if (!/^[A-Za-z0-9._-]+$/.test(REF)) {
  console.error(`Invalid git ref: ${REF}`);
  process.exit(2);
}

// Minimal server that serves only test/scittle-cdn-test.html
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      const urlPath = new URL(req.url, "http://localhost").pathname;
      // Only serve the CDN test HTML
      if (urlPath === "/" || urlPath === "/scittle-cdn-test.html") {
        try {
          const html = await readFile(join(TEST_DIR, "scittle-cdn-test.html"), "utf8");
          res.writeHead(200, { "Content-Type": "text/html" });
          res.end(html.replaceAll("canonical-edn@main/", `canonical-edn@${REF}/`));
        } catch {
          res.writeHead(404);
          res.end("Not found");
        }
      } else {
        res.writeHead(404);
        res.end("Not found — CDN test serves only the HTML; CEDN loads from jsdelivr");
      }
    });
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      resolve({ server, port });
    });
  });
}

async function run() {
  const { server, port } = await startServer();
  let browser;
  try {
    console.log(`Serving CDN test at http://127.0.0.1:${port}/`);
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();

    // Forward browser console output to stdout
    let loadedVersion = null;
    page.on("console", (msg) => {
      const text = msg.text();
      const m = text.match(/^cedn version: (\S+)/);
      if (m) loadedVersion = m[1];
      console.log(text);
    });

    const url = `http://127.0.0.1:${port}/scittle-cdn-test.html`;
    console.log(`Navigating to ${url}`);
    console.log(`CEDN source loading from jsdelivr CDN (@${REF})...\n`);
    await page.goto(url);

    // Wait for the test framework to set window.cednTestResults
    const results = await page.waitForFunction(
      () => window.cednTestResults,
      null,
      { timeout: TIMEOUT_MS }
    );
    const { pass, fail, total } = await results.jsonValue();

    console.log(`\nScittle CDN results: ${pass} passed, ${fail} failed (${total} total)`);
    if (fail > 0) {
      process.exitCode = 1;
    }
    // A release tag must serve that release, not a cached or mis-tagged build.
    if (/^v\d+\.\d+\.\d+$/.test(REF) && loadedVersion !== REF.slice(1)) {
      console.error(`@${REF} served cedn version ${loadedVersion}, expected ${REF.slice(1)}`);
      process.exitCode = 1;
    }
  } catch (err) {
    console.error("Scittle CDN test runner error:", err.message);
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    server.close();
  }
}

run();
