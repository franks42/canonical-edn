#!/usr/bin/env node
// Automated Scittle browser test runner using Playwright.
// Starts a local HTTP server, launches headless Chromium, runs scittle-tests.html,
// and reports pass/fail based on window.cednTestResults.

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { join, extname } from "node:path";
import { chromium } from "playwright";

const PROJECT_ROOT = new URL("..", import.meta.url).pathname;
const TIMEOUT_MS = 60_000;

const MIME_TYPES = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".cljc": "text/plain",
  ".clj": "text/plain",
  ".cljs": "text/plain",
  ".edn": "text/plain",
  ".css": "text/css",
  ".json": "application/json",
};

// Minimal static file server rooted at PROJECT_ROOT.
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      // Strip query string (Scittle uses ?v=1 cache busting)
      const urlPath = new URL(req.url, "http://localhost").pathname;
      const filePath = join(PROJECT_ROOT, urlPath);
      try {
        const data = await readFile(filePath);
        const ext = extname(urlPath);
        res.writeHead(200, {
          "Content-Type": MIME_TYPES[ext] || "application/octet-stream",
        });
        res.end(data);
      } catch {
        res.writeHead(404);
        res.end("Not found");
      }
    });
    // Port 0 = OS picks a free port
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
    console.log(`Serving project at http://127.0.0.1:${port}/`);
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();

    // Forward browser console output to stdout
    page.on("console", (msg) => console.log(msg.text()));

    const url = `http://127.0.0.1:${port}/scittle-tests.html`;
    console.log(`Navigating to ${url}\n`);
    await page.goto(url);

    // Wait for the test framework to set window.cednTestResults
    const results = await page.waitForFunction(
      () => window.cednTestResults,
      { timeout: TIMEOUT_MS }
    );
    const { pass, fail, total } = await results.jsonValue();

    console.log(`\nScittle results: ${pass} passed, ${fail} failed (${total} total)`);
    if (fail > 0) {
      process.exitCode = 1;
    }
  } catch (err) {
    console.error("Scittle test runner error:", err.message);
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    server.close();
  }
}

run();
