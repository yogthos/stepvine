// End-to-end storyboard: drives a real Chromium against the running app to
// verify the key workflows actually work in the browser (datastar JS runs, SSE
// connects, server-computed fields update). Run with the dev server on :3000.
import { chromium } from 'playwright';

const BASE = process.env.BASE || 'http://localhost:3000';
let failures = 0;
const ok  = (m) => console.log(`  ✓ ${m}`);
const bad = (m) => { failures++; console.log(`  ✗ ${m}`); };
const step = (n) => console.log(`\n=== ${n} ===`);

// fail the whole run on any uncaught page error (e.g. datastar engine crash)
const pageErrors = [];
function watch(page, label) {
  page.on('pageerror', (e) => pageErrors.push(`[${label}] ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(`[${label}] console: ${m.text()}`); });
}

const val   = (page, sel) => page.locator(sel).inputValue();
const text  = (page, sel) => page.locator(sel).innerText();
async function waitVal(page, sel, pred, ms = 6000) {
  await page.waitForFunction(
    ([s, p]) => { const el = document.querySelector(s); return el && eval(p)(el.value); },
    [sel, pred.toString()], { timeout: ms });
}

// datastar loads as an async ES module and only takes control of the form once
// it has initialized and opened the data-init SSE stream. Inputs filled before
// that aren't datastar-bound yet, and init then re-applies the data-signals seed
// (resetting them). So open every doc via this helper: it arms the /sse wait
// *before* navigating (datastar is cached after first load, so the stream can
// open before a post-hoc listener attaches), then waits for the stream to
// connect (== datastar is live) before returning — exactly what a real user's
// reaction time gives for free.
async function openDoc(page, navigate, readySel, ms = 5000) {
  const sse = page.waitForRequest((r) => /\/sse/.test(r.url()), { timeout: ms });
  await navigate();
  if (readySel) await page.waitForSelector(readySel);
  await sse;
  await page.waitForTimeout(150); // let the on-connect catch-up sync apply
}

const browser = await chromium.launch();
const ctx = await browser.newContext();
const page = await ctx.newPage();
watch(page, 'main');

try {
  // ---- 1. Auth ----------------------------------------------------------
  step('1. Authentication');
  await page.goto(BASE + '/');
  if (page.url().includes('/login')) ok('anonymous redirected to /login'); else bad('no auth gate');
  await page.fill('input[name=username]', 'admin');
  await page.fill('input[name=password]', 'admin');
  await page.click('button:has-text("Sign in")');
  await page.waitForLoadState('networkidle');
  (await page.content()).includes('Stepvine documents') ? ok('logged in, landing shown') : bad('login failed');

  // ---- 2. BMI reactive calculation (the regression) ---------------------
  step('2. BMI calculator — live server computation');
  page.on('request', (r) => { if (/\/field\/|\/sse/.test(r.url())) console.log('   >>', r.method(), r.url().replace(BASE,''), r.postData() || ''); });
  page.on('response', (r) => { if (/\/field\//.test(r.url())) console.log('   <<', r.status(), r.url().replace(BASE,'')); });
  await openDoc(page, () => page.click('button:has-text("New BMI Calculator")'), '#kg');
  ok('opened a BMI document');
  await page.fill('#kg', '70');
  await page.waitForTimeout(700);
  await page.fill('#m', '1.75');
  // wait for the value to settle on the m-included result (kg-only posts 70 first)
  await waitVal(page, '#bmi', (v) => Math.abs(parseFloat(v) - 22.857) < 0.01);
  const bmi1 = await val(page, '#bmi');
  Math.abs(parseFloat(bmi1) - 22.857) < 0.01 ? ok(`BMI computed live: ${bmi1}`) : bad(`BMI wrong: ${bmi1}`);
  (await text(page, '[data-text="$bmi_category"]')) === 'healthy' ? ok('category = healthy') : bad('category wrong');
  (await page.locator('p.warning').isVisible()) ? bad('warning shown when healthy') : ok('overweight warning hidden');

  await page.fill('#kg', '100');
  await page.fill('#m', '2');
  await waitVal(page, '#bmi', (v) => v === '25');
  ok('BMI recomputed to 25 on change');
  (await text(page, '[data-text="$bmi_category"]')) === 'overweight' ? ok('category = overweight') : bad('category wrong');
  (await page.locator('p.warning').isVisible()) ? ok('overweight warning now shown') : bad('warning not shown');

  // ---- 3. Export action -------------------------------------------------
  step('3. Export action');
  await page.click('button:has-text("Export summary")');
  await page.waitForFunction(() => document.querySelector('#export-result')?.innerText.includes('Observation'), null, { timeout: 5000 });
  (await text(page, '#export-result')).includes('"bmi"') ? ok('export rendered with bmi value') : bad('export missing');

  const bmiDocUrl = page.url();

  // ---- 4. Collection (roster) — add item, per-item compute --------------
  step('4. Collection — add member, per-item derived fields');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Team Roster")'), 'button.add');
  await page.click('button.add');
  await page.waitForSelector('.coll-item input');
  ok('added a member row');
  const firstIn = page.locator('.coll-item input').nth(0);
  const lastIn  = page.locator('.coll-item input').nth(1);
  await firstIn.fill('Grace');
  await lastIn.fill('Hopper');
  await page.waitForFunction(() => document.querySelector('.coll-item [data-text^="$members_"]')?.innerText === 'Grace Hopper', null, { timeout: 5000 });
  ok('per-item full name computed: Grace Hopper');
  const spans = await page.locator('.coll-item span[data-text]').allInnerTexts();
  spans.includes('GH') ? ok('per-item reaction (initials) computed: GH') : bad(`initials missing: ${spans}`);
  await page.click('.coll-item button.remove');
  await page.waitForFunction(() => !document.querySelector('.coll-item'), null, { timeout: 5000 });
  ok('member removed');

  // ---- 5. Form builder --------------------------------------------------
  step('5. Visual form builder');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Form builder")'), '#form-id');
  await page.fill('#form-id', 'survey');
  await page.fill('#form-title', 'Survey');
  await page.click('button.add');
  await page.waitForSelector('.coll-item input');
  await page.locator('.coll-item input').nth(0).fill('q1');     // field id
  await page.locator('.coll-item input').nth(1).fill('Question 1'); // label
  await page.locator('.coll-item select').selectOption('number'); // item-scoped dropdown bind
  await page.waitForTimeout(500);
  await page.click('button.build');
  await page.waitForFunction(() => document.querySelector('#build-result')?.innerText.includes('Built form'), null, { timeout: 5000 });
  ok('built form "survey"');
  await page.goto(BASE + '/');
  (await page.content()).includes('/form/survey/new') ? ok('new "survey" form available on landing') : bad('built form not on landing');
  // open a survey doc and confirm the dropdown-selected field type was applied
  await openDoc(page, () => page.click('button:has-text("New Survey")'), '#q1');
  (await page.locator('#q1').getAttribute('type')) === 'number'
    ? ok('item dropdown bound: q1 built as type=number')
    : bad(`field type not applied: q1 type=${await page.locator('#q1').getAttribute('type')}`);

  // ---- 6. Multi-user presence + live propagation ------------------------
  step('6. Two users — live propagation + presence');
  const ctx2 = await browser.newContext();
  const page2 = await ctx2.newPage();
  watch(page2, 'peer');
  await page2.goto(BASE + '/login');
  await page2.fill('input[name=username]', 'admin');
  await page2.fill('input[name=password]', 'admin');
  await page2.click('button:has-text("Sign in")');
  await page2.waitForLoadState('networkidle');
  await openDoc(page,  () => page.goto(bmiDocUrl),  '#kg'); // user A back on the BMI doc
  await openDoc(page2, () => page2.goto(bmiDocUrl), '#kg'); // user B opens the same doc
  await page2.fill('#kg', '60'); await page2.fill('#m', '2');
  // user A should see B's edit reflected via SSE
  await page.waitForFunction(() => document.querySelector('#bmi')?.value === '15', null, { timeout: 5000 })
    .then(() => ok("user A saw user B's edit live (BMI 15)"))
    .catch(() => bad('live propagation between users failed'));
  await ctx2.close();

  // ---- console / page errors -------------------------------------------
  step('Console / page errors');
  if (pageErrors.length === 0) ok('no uncaught page or console errors');
  else { pageErrors.forEach((e) => bad(e)); }

} catch (e) {
  bad('storyboard threw: ' + e.message);
  console.log(e.stack);
} finally {
  await browser.close();
}

console.log(`\n${failures === 0 ? 'ALL WORKFLOWS PASSED' : failures + ' FAILURE(S)'}`);
process.exit(failures === 0 ? 0 : 1);
