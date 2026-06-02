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

  // ---- 7. Table widget — add / sort / page ------------------------------
  step('7. Table widget — add, sort, page');
  const tTitles = async () => {
    const c = await page.locator('tbody tr[data-table-row-idx]').count();
    const out = [];
    for (let i = 0; i < c; i++) out.push(await page.locator('tbody tr[data-table-row-idx]').nth(i).locator('input').nth(0).inputValue());
    return out;
  };
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Task List")'), '.widget-table');
  ok('opened a table document');
  // three filled rows (all fit on page 1, size 3) for clean sort assertions
  for (let i = 0; i < 3; i++) { await page.click('.widget-table-add-row'); await page.waitForTimeout(250); }
  const tdata = [['Charlie', '3'], ['Alice', '1'], ['Bob', '2']];
  for (let i = 0; i < 3; i++) {
    const r = page.locator('tbody tr[data-table-row-idx]').nth(i);
    await r.locator('input').nth(0).fill(tdata[i][0]); await r.locator('input').nth(1).fill(tdata[i][1]);
    await page.waitForTimeout(150);
  }
  await page.waitForTimeout(300);
  await page.click('th.widget-table-sortable-column:has-text("Title")'); await page.waitForTimeout(400);
  JSON.stringify(await tTitles()) === JSON.stringify(['Alice', 'Bob', 'Charlie']) ? ok('sort by title ascending') : bad(`sort asc wrong: ${await tTitles()}`);
  await page.click('th.widget-table-sortable-column:has-text("Title")'); await page.waitForTimeout(400);
  JSON.stringify(await tTitles()) === JSON.stringify(['Charlie', 'Bob', 'Alice']) ? ok('sort by title descending') : bad(`sort desc wrong: ${await tTitles()}`);
  await page.click('th.widget-table-sortable-column:has-text("Priority")'); await page.waitForTimeout(400);
  JSON.stringify(await tTitles()) === JSON.stringify(['Alice', 'Bob', 'Charlie']) ? ok('sort by priority (numeric)') : bad(`sort prio wrong: ${await tTitles()}`);
  // a 4th row exercises paging (size 3 -> 2 pages)
  await page.click('.widget-table-add-row'); await page.waitForTimeout(300);
  (await page.locator('.widget-table-pager').innerText()).includes('of 2') ? ok('4th row creates page 2') : bad('paging info wrong');
  await page.click('.widget-table-pager a:has-text("»")'); await page.waitForTimeout(400);
  (await page.locator('.widget-table-pager').innerText()).includes('Page 2 of 2') ? ok('paged to page 2') : bad('next page failed');

  // ---- 8. Widget showcase — all widgets render + reactivity -------------
  step('8. Widget showcase — render + reactivity');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Widget Showcase")'), '#name');
  ok('opened the widget showcase');
  // every widget kind is present
  const present = await page.evaluate(() => [
    '.widget.dropdown', '.widget.radio', '.widget.selections', '.widget.menu',
    '.widget.checkbox', '.widget.slider', '.widget.date-picker', '.widget.input-time',
    '.widget.typeahead', '.widget.textarea', '.widget-table',
  ].filter((s) => !document.querySelector(s)));
  present.length === 0 ? ok('all widget kinds rendered') : bad(`missing widgets: ${present}`);
  // reaction: greeting follows the name field
  await page.fill('#name', 'World'); await page.waitForTimeout(500);
  (await page.locator('[data-text="$greeting"]').innerText()) === 'Hello, World!'
    ? ok('greeting reaction updated live') : bad(`greeting wrong: ${await page.locator('[data-text="$greeting"]').innerText()}`);
  // slider drives a category reaction + conditional alert
  await page.locator('#rating').fill('8'); await page.waitForTimeout(500);
  (await page.locator('[data-text="$rating_label"]').innerText()) === 'high'
    ? ok('slider drove rating-label reaction = high') : bad(`rating-label wrong: ${await page.locator('[data-text="$rating_label"]').innerText()}`);
  (await page.locator('.widget.alert').isVisible())
    ? ok('conditional alert shown for high rating') : bad('alert not shown');

  // ---- 9. Document lifecycle — submit locks, revise reopens -------------
  step('9. Document lifecycle — submit / revise');
  await page.click('.submit-btn');
  await page.waitForTimeout(500);
  (await page.locator('.sv-status').isVisible())
    ? ok('submit finalized the document (read-only banner shown)') : bad('no read-only banner after submit');
  (await page.locator('.submit-btn').isVisible())
    ? bad('submit button still visible after submit') : ok('submit button hidden once finalized');
  (await page.locator('.revise-btn').isVisible())
    ? ok('revise button shown while locked') : bad('revise button not shown while locked');
  await page.click('.revise-btn');
  await page.waitForTimeout(500);
  (await page.locator('.sv-status').isVisible())
    ? bad('read-only banner still shown after revise') : ok('revise reopened the document');
  (await page.locator('.submit-btn').isVisible())
    ? ok('submit button restored after revise') : bad('submit button not restored after revise');

  // ---- 10. Workflow state machine — ticket open→review→closed ----------
  step('10. Workflow state machine — mycelium FSM');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Support Ticket")'), '#title');
  const stateText = () => page.locator('[data-text="$state"]').innerText();
  ok('opened a ticket');
  (await stateText()) === 'open' ? ok('initial state :open') : bad(`state not open: ${await stateText()}`);
  await page.fill('#title', 'Printer down'); await page.waitForTimeout(450);
  (await page.locator('.wf-btn:has-text("Submit for review")').isVisible())
    ? ok('submit action shown in :open') : bad('submit action not shown in :open');
  await page.click('.wf-btn:has-text("Submit for review")'); await page.waitForTimeout(600);
  (await stateText()) === 'review' ? ok('FSM transitioned :open → :review') : bad(`state not review: ${await stateText()}`);
  (await page.locator('.wf-btn:has-text("Submit for review")').isVisible())
    ? bad('submit still shown after transition') : ok('submit hidden once out of :open');
  (await page.locator('.wf-btn:has-text("Approve")').isVisible())
    ? ok('approve/reject shown in :review') : bad('approve not shown in :review');
  await page.click('.wf-btn:has-text("Approve")'); await page.waitForTimeout(600);
  (await stateText()) === 'closed' ? ok('FSM transitioned :review → :closed') : bad(`state not closed: ${await stateText()}`);
  (await page.locator('.sv-status').isVisible())
    ? ok('terminal :closed state is read-only') : bad('closed document not read-only');
  // the :pdf step generated a downloadable report
  const reportHref = await page.locator('a:has-text("Download report PDF")').getAttribute('href');
  const pdfResp = await page.request.get(BASE + reportHref);
  const pdfHead = (await pdfResp.body()).subarray(0, 4).toString('latin1');
  (pdfResp.status() === 200 && pdfHead === '%PDF')
    ? ok('approve generated a downloadable PDF report') : bad(`PDF report bad: ${pdfResp.status()} ${pdfHead}`);

  // ---- 11. Index lookup — create a prepopulated document from a key -----
  step('11. Index lookup — prepopulated creation');
  await page.goto(BASE + '/');
  await page.click('a:has-text("New Patient Intake")');         // index forms link to a lookup page
  await page.waitForSelector('input[name=index-key]');
  ok('index form opens a key-lookup page');
  await page.fill('input[name=index-key]', 'p1');
  await Promise.all([page.waitForURL(/\/doc\//), page.click('button:has-text("Look up & create")')]);
  await page.waitForTimeout(500);
  (await page.inputValue('#fname')) === 'Ada'
    ? ok('lookup prepopulated the document (Ada Lovelace)') : bad(`fname not prepopulated: ${await page.inputValue('#fname')}`);
  await page.goto(BASE + '/?status=in-progress');
  (await page.locator('.badge:has-text("in-progress")').first().isVisible())
    ? ok('document list filters + shows status badges') : bad('status badge/filter missing');

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
