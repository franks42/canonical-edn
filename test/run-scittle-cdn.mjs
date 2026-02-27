#!/usr/bin/env node
// Automated Scittle CDN test runner using Playwright.
// Serves only the test HTML locally — CEDN source loads from jsdelivr CDN.

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { join, extname } from "node:path";
import { chromium } from "playwright";

const TEST_DIR = new URL(".", import.meta.url).pathname;
const TIMEOUT_MS = 60_000;

// Minimal server that serves only test/scittle-cdn-test.html
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      const urlPath = new URL(req.url, "http://localhost").pathname;
      // Only serve the CDN test HTML
      if (urlPath === "/" || urlPath === "/scittle-cdn-test.html") {
        try {
          const data = await readFile(join(TEST_DIR, "scittle-cdn-test.html"));
          res.writeHead(200, { "Content-Type": "text/html" });
          res.end(data);
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
    page.on("console", (msg) => console.log(msg.text()));

    const url = `http://127.0.0.1:${port}/scittle-cdn-test.html`;
    console.log(`Navigating to ${url}`);
    console.log("CEDN source loading from jsdelivr CDN...\n");
    await page.goto(url);

    // Wait for the test framework to set window.cednTestResults
    const results = await page.waitForFunction(
      () => window.cednTestResults,
      { timeout: TIMEOUT_MS }
    );
    const { pass, fail, total } = await results.jsonValue();

    console.log(`\nScittle CDN results: ${pass} passed, ${fail} failed (${total} total)`);
    if (fail > 0) {
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
