// 将 docs/assets/src 下的架构图 HTML 渲染为 docs/assets 下的高清 PNG。
// 复用项目 apps/web 中的 playwright-core 与本机 Playwright 缓存的 headless shell。
const { chromium } = require('../../../apps/web/node_modules/playwright-core');
const path = require('path');
const { pathToFileURL } = require('url');

const exe = path.join(
  process.env.USERPROFILE,
  'AppData', 'Local', 'ms-playwright',
  'chromium_headless_shell-1243', 'chrome-headless-shell-win64', 'chrome-headless-shell.exe'
);

const jobs = [
  ['architecture-overview.html', 'architecture-overview.png'],
  ['agent-collaboration.html', 'agent-collaboration.png'],
  ['planning-flow.html', 'planning-flow.png'],
];

(async () => {
  const browser = await chromium.launch({ executablePath: exe });
  for (const [html, png] of jobs) {
    const page = await browser.newPage({
      viewport: { width: 1600, height: 400 },
      deviceScaleFactor: 2,
    });
    await page.goto(pathToFileURL(path.resolve(__dirname, html)).href);
    await page.waitForTimeout(300);
    const out = path.resolve(__dirname, '..', png);
    await page.screenshot({ path: out, fullPage: true });
    console.log('rendered:', out);
    await page.close();
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
