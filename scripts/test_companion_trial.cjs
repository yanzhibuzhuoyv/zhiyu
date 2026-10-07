/* Real browser + synthetic HTTP server. No credentials from the user's machine. */
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {chromium} = require(path.join(process.env.ZHIYU_NODE_MODULES, "playwright"));
const URL = "http://127.0.0.1:8799/trial";
const output = path.resolve(__dirname, "../artifacts/companion-preset-browser.json");
const checks = [];
const mark = label => checks.push(label);
const waitStatus = (page, text) => page.locator("#status").filter({hasText: text}).waitFor();

async function waitForFixture() {
  for (let attempt = 0; attempt < 25; attempt++) {
    try {
      const response = await fetch("http://127.0.0.1:8799/__ui_fixture");
      if (response.ok && (await response.json()).synthetic_provider === true) return;
    } catch { /* The owned helper may still be starting; never send a key before its marker. */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error("Synthetic fixture readiness marker unavailable");
}

async function login(page, key) {
  await page.locator("#key").fill(key);
  await page.locator("#connect").click();
  await page.locator("#chat").waitFor({state: "visible"});
}

async function testEntryAndConnect(page) {
  await page.goto(URL);
  await page.locator("#connect:not([disabled])").waitFor();
  assert.match(await page.locator(".vow").innerText(), /恋人仅此唯一/);
  await page.locator("#key").fill("invalid-key");
  await page.locator("#connect").click();
  await waitStatus(page, "测试 Key 无效");
  assert.equal(await page.locator("#provider").isEnabled(), true);
  mark("Invalid key does not bind provider/profile");
  const creation = page.waitForResponse(response => response.url().endsWith("/api/conversations") && response.request().method() === "POST");
  await login(page, "synthetic-original-key");
  assert.equal((await (await creation).json()).companion_profile.style, "warm_v2");
  assert.equal(await page.locator("#messages .bubble").count(), 0);
  mark("Real HTTP login and asserted warm_v2 conversation creation");
}

async function testSendingAndEscaping(page) {
  await page.locator("#message").fill("日常测试：今天的花开了");
  await page.locator("#send").click();
  assert.equal(await page.locator("#message").isDisabled(), true);
  await page.locator("#messages .bubble.assistant").waitFor();
  assert.equal(await page.locator("#message").inputValue(), "");
  assert.match(await page.locator("#messages").innerText(), /<img src=x/);
  assert.equal(await page.locator("#messages img").count(), 0);
  mark("Message field locked while awaiting; successful real API fields render");
  mark("Untrusted answer markup is displayed as text");
}

async function testRefreshAndKeyBoundary(page) {
  await page.reload();
  await page.locator("#connect:not([disabled])").waitFor();
  assert.equal(await page.locator("#provider").isDisabled(), true);
  assert.equal(await page.locator("#key").inputValue(), "");
  await page.locator("#key").fill("synthetic-other-key");
  await page.locator("#connect").click();
  await waitStatus(page, "本页不会创建另一段关系");
  assert.equal(await page.locator("#entry").isVisible(), true);
  mark("Reload keeps binding, never retains API key");
  mark("Different valid key cannot silently create another conversation");
  await login(page, "synthetic-original-key");
  assert.equal(await page.locator("#messages .bubble").count(), 2);
  mark("Original key restores the same two saved originals");
}

async function testHttpFailure(page) {
  await page.locator("#message").fill("trigger-http-failure");
  await page.locator("#send").click();
  await waitStatus(page, "模拟服务商限流");
  assert.equal(await page.locator("#message").inputValue(), "trigger-http-failure");
  assert.equal(await page.locator("#send").isEnabled(), true);
  assert.equal(await page.locator("#messages .bubble").count(), 2);
  mark("Known HTTP failure preserves draft and saved history");
}

async function testSavedButLostResponse(page) {
  let lost = false, posts = 0;
  await page.route("**/api/conversations/*/messages", async route => {
    posts += 1;
    if (!lost) {
      lost = true;
      const response = await route.fetch();
      assert.equal(response.status(), 200);
      await route.abort("failed");
    } else await route.continue();
  });
  await page.locator("#message").fill("回复已保存但丢失网络响应的测试");
  await page.locator("#send").click();
  await waitStatus(page, "回复已经保存");
  assert.equal(posts, 1);
  assert.equal(await page.locator("#messages .bubble").count(), 4);
  assert.equal(await page.locator("#message").inputValue(), "");
  mark("Saved response lost: recover history without retrying POST");
  await page.unroute("**/api/conversations/*/messages");
}

async function testUnknownFailureAndDisconnect(page) {
  await page.route("**/api/conversations/*/messages", route => route.abort("failed"));
  await page.locator("#message").fill("尚未确认是否保存的测试");
  await page.locator("#send").click();
  await waitStatus(page, "暂时无法确认回复是否仍在处理中");
  assert.equal(await page.locator("#send").isDisabled(), true);
  assert.equal(await page.locator("#message").inputValue(), "尚未确认是否保存的测试");
  mark("Unknown network outcome blocks immediate resend");
  await page.unroute("**/api/conversations/*/messages");
  await page.locator("#disconnect").click();
  await waitStatus(page, "已断开连接");
  assert.equal(await page.locator("#provider").isDisabled(), true);
  await login(page, "synthetic-original-key");
  assert.equal(await page.locator("#messages .bubble").count(), 4);
  mark("Disconnect preserves preset and conversation binding");
}

async function run() {
  await waitForFixture();
  const browser = await chromium.launch({headless: true, executablePath: process.env.ZHIYU_CHROMIUM});
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  try {
    await testEntryAndConnect(page);
    await testSendingAndEscaping(page);
    await testRefreshAndKeyBoundary(page);
    await testHttpFailure(page);
    await testSavedButLostResponse(page);
    await testUnknownFailureAndDisconnect(page);
    assert.deepEqual(errors, []);
    mark("No uncaught JavaScript errors across all interactions");
    fs.writeFileSync(output, JSON.stringify({passed: true, checks, page_errors: errors,
      limitation: "No server idempotency protocol; in-flight request across immediate reconnect remains uncertain."}, null, 2));
    console.log(JSON.stringify({passed: true, checks: checks.length, output}));
  } finally { await browser.close(); }
}
run().catch(error => {
  fs.writeFileSync(output, JSON.stringify({passed: false, checks, error: error.message}, null, 2));
  console.error(error.message);
  process.exitCode = 1;
});
