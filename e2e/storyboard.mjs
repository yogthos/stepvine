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
// Blur the focused field so its lock/unlock POST flushes, then let it land. A
// real user gets this for free in the beat before clicking a nav link; without
// it a page navigation can abort an in-flight field request.
async function settleFields(page, ms = 600) {
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await page.waitForTimeout(ms);
}

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
  (await page.locator('.sv-topbar .sv-brand').isVisible()) ? ok('logged in, landing shown') : bad('login failed');
  // shared chrome: navbar (user + breadcrumbs) + footer
  (await page.locator('.sv-user').innerText()).includes('Admin') ? ok('navbar shows the signed-in user') : bad('user not shown in navbar');
  (await page.locator('.sv-crumbs').isVisible()) ? ok('breadcrumbs present') : bad('no breadcrumbs');
  (await page.locator('.sv-footer').isVisible()) ? ok('footer present') : bad('no footer');

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
  // the editor carries the chrome with a breadcrumb back to the document list
  (await page.locator('.sv-topbar .sv-crumbs a:has-text("Documents")').getAttribute('href')) === '/'
    ? ok('editor breadcrumb links back to documents') : bad('editor breadcrumb missing/wrong');
  (await page.locator('.sv-crumbs').innerText()).includes('Widget Showcase')
    ? ok('breadcrumb shows the current form') : bad('breadcrumb missing form name');
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
  // the app carries its own styling, served live and layered over the platform CSS
  (await page.locator('link[href^="/app/ticket/style.css"]').count()) > 0
    ? ok('the ticket app links its own CSS') : bad('app CSS not linked in the editor');
  const btnBg = await page.locator('.wf-btn').first().evaluate((el) => getComputedStyle(el).backgroundColor);
  btnBg === 'rgb(13, 148, 136)'
    ? ok('app CSS applied (re-skinned the action button)') : bad(`app CSS not applied: ${btnBg}`);
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
  // the :email step on submit sent a templated message (recorded in the dev outbox)
  await page.goto(BASE + '/admin/outbox');
  (await page.locator('td:has-text("New ticket for review: Printer down")').count()) > 0
    ? ok('the :email workflow step sent a templated message (in the outbox)')
    : bad('email step did not record a message in the outbox');

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

  // ---- 12. OAuth / OIDC — mock SSO login -------------------------------
  step('12. OAuth / OIDC — mock SSO login');
  const octx = await browser.newContext();           // fresh session, so admin is untouched
  const opage = await octx.newPage();
  watch(opage, 'sso');
  await opage.goto(BASE + '/login');
  (await opage.locator('a.oauth:has-text("Demo SSO")').isVisible())
    ? ok('login page offers the SSO provider') : bad('SSO provider link missing');
  await Promise.all([opage.waitForURL(BASE + '/'), opage.click('a.oauth:has-text("Demo SSO")')]);
  (await opage.locator('.sv-topbar .sv-brand').isVisible())
    ? ok('mock SSO logged in and reached the app') : bad('SSO did not reach the app');
  await octx.close();

  // ---- 13. Admin UI — role-based form access ---------------------------
  step('13. Admin UI — role-based form access');
  // admin restricts the ticket form to the :reviewer role
  await page.goto(BASE + '/admin/forms');
  ok('admin can open the admin UI');
  const fRow = page.locator('tr', { hasText: 'Support Ticket' });
  await fRow.locator('input[name=roles]').fill('reviewer');
  await fRow.locator('button:has-text("Save")').click();
  await page.waitForTimeout(400);
  // a fresh non-admin user registers (no roles)
  const rctx = await browser.newContext();
  const rpage = await rctx.newPage();
  watch(rpage, 'rbac');
  await rpage.goto(BASE + '/register');
  await rpage.fill('input[name=username]', 'rbac-user');
  await rpage.fill('input[name=display-name]', 'RBAC User');
  await rpage.fill('input[name=password]', 'pw');
  await Promise.all([rpage.waitForURL(BASE + '/'), rpage.click('button:has-text("Create account")')]);
  (await rpage.locator('h2:has-text("Support Ticket")').count()) === 0
    ? ok('a restricted form is hidden from a user without the role')
    : bad('restricted form leaked to a user without the role');
  // admin grants the user the :reviewer role
  await page.goto(BASE + '/admin/users');
  const uRow = page.locator('tr', { hasText: 'rbac-user' });
  await uRow.locator('input[name=roles]').fill('reviewer');
  await uRow.locator('button:has-text("Save")').click();
  await page.waitForTimeout(400);
  // the user now sees the form
  await rpage.goto(BASE + '/');
  (await rpage.locator('h2:has-text("Support Ticket")').count()) > 0
    ? ok('granting the role reveals the form to the user')
    : bad('form still hidden after the role was granted');
  // a non-admin cannot reach the admin UI
  await rpage.goto(BASE + '/admin/users');
  (rpage.url().replace(/\/$/, '') === BASE)
    ? ok('non-admin is redirected away from the admin UI')
    : bad(`non-admin reached admin UI: ${rpage.url()}`);
  await rctx.close();

  // ---- 14. Admin live app editor — create, preview, save ---------------
  step('14. Admin live app editor');
  await page.goto(BASE + '/admin/forms');
  await page.fill('input[name=id]', 'demo-app');
  await page.fill('input[name=title]', 'Demo App');
  await Promise.all([page.waitForURL(/\/admin\/forms\/demo-app\/edit/),
                     page.click('button:has-text("Create & edit")')]);
  ok('created a new app and opened the editor');
  await page.waitForSelector('.cm-editor', { timeout: 20000 });   // CodeMirror from the CDN
  ok('CodeMirror editor mounted');
  // the editor page keeps the signed-in user in the navbar (not "Sign in")
  (await page.locator('.sv-user').innerText()).includes('Admin') && !(await page.locator('.sv-signin').count())
    ? ok('editor navbar shows the signed-in admin') : bad('editor navbar shows "Sign in" while logged in');
  // the live preview renders the scaffold inside the iframe
  await page.waitForTimeout(2000);
  (await page.frameLocator('#preview').locator('h1:has-text("Demo App")').count()) > 0
    ? ok('live preview rendered the form') : bad('live preview did not render');
  // a fresh preview must NOT show the submitted/read-only lifecycle banner
  (await page.frameLocator('#preview').locator('.sv-status').isVisible().catch(() => false))
    ? bad('preview shows the read-only banner on a fresh form')
    : ok('preview is clean (no spurious read-only banner)');
  // save persists the app to the DB
  await page.click('#save');
  await page.waitForTimeout(800);
  (await page.locator('#savemsg').innerText()).includes('Saved')
    ? ok('saved the app live') : bad(`save failed: ${await page.locator('#savemsg').innerText()}`);
  // the new app is now available
  await page.goto(BASE + '/');
  (await page.locator('h2:has-text("Demo App")').count()) > 0
    ? ok('the new app appears on the landing') : bad('new app not on the landing');

  // a multi-page form previews its first page (not blank) + offers a view picker
  await page.goto(BASE + '/admin/forms/onboarding/edit');
  await page.waitForSelector('.cm-editor', { timeout: 20000 });
  await page.waitForTimeout(1500);
  (await page.frameLocator('#preview').locator('h1:has-text("Account")').count()) > 0
    ? ok('multi-page form previews its first page') : bad('multi-page preview was blank');
  await page.waitForSelector('#preview-view');
  (await page.locator('#preview-view option').count()) === 3
    ? ok('preview view picker lists all three pages') : bad('view picker missing pages');
  // switch the previewed page
  await page.selectOption('#preview-view', 'review');
  await page.waitForTimeout(800);
  (await page.frameLocator('#preview').locator('h1:has-text("Review")').count()) > 0
    ? ok('picking a view re-renders that page in the preview') : bad('view picker did not switch the preview');

  // ---- 15. Multi-page form — in-place page switch, URLs, validation gating
  step('15. Multi-page form navigation (no-reload + gating)');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Onboarding")'), '#full-name');
  ok('opened a multi-page onboarding form');
  (await page.locator('.sv-pages .sv-page').count()) === 3
    ? ok('three page tabs shown') : bad('page tabs missing');
  (await page.locator('.sv-page.active').innerText()).includes('Account')
    ? ok('Account is the active page') : bad('wrong active page');
  // mark the window so we can later prove no full reload happened
  await page.evaluate(() => { window.__noReload = true; });

  // -- validation gating: Next is disabled until the page is valid -----------
  (await page.locator('.sv-pagenav-next').getAttribute('aria-disabled')) === 'true'
    ? ok('Next is gated (disabled) on an empty page') : bad('Next not gated initially');
  (await page.locator('.sv-page-hint').isVisible())
    ? ok('a "complete this page" hint is shown') : bad('no gating hint shown');
  // force a click through the gate — the datastar guard still blocks it
  await page.click('.sv-pagenav-next', { force: true });
  await page.waitForTimeout(300);
  (await page.locator('.sv-page.active').innerText()).includes('Account')
    ? ok('gated Next did not advance (guard blocks the click)') : bad('gated Next advanced anyway');

  // fill the page validly → Next ungates live (server pushes the $ready reaction)
  await page.fill('#full-name', 'Ada Lovelace');
  await page.fill('#email', 'ada@example.com');
  await settleFields(page);
  await page.waitForFunction(() => {
    const a = document.querySelector('.sv-pagenav-next');
    return a && a.getAttribute('aria-disabled') === 'false';
  }, null, { timeout: 4000 }).then(() => ok('Next ungated once the page is valid'))
    .catch(() => bad('Next stayed gated after valid input'));

  // -- in-place switch to Profile (no reload), URL updates -------------------
  await page.click('.sv-pagenav-next');
  await page.waitForSelector('#role');                 // morphed in, no navigation
  (await page.evaluate(() => window.__noReload === true))
    ? ok('page switched in place (no full reload)') : bad('a full reload happened');
  (await page.locator('.sv-page.active').innerText()).includes('Profile')
    ? ok('Next advanced to Profile in place') : bad('Next did not advance');
  page.url().includes('view=profile')
    ? ok('the URL reflects the current page (linkable)') : bad(`URL not updated: ${page.url()}`);
  await page.selectOption('#role', 'engineer').catch(() => {});
  await page.fill('#bio', 'Mathematician').catch(() => {});
  await settleFields(page);

  // -- jump to Review via a breadcrumb tab (in place) ------------------------
  await page.click('.sv-page:has-text("Review")');
  await page.waitForFunction(
    () => (document.querySelector('.sv-page.active') || {}).textContent?.includes('Review'),
    null, { timeout: 4000 });
  ok('jumped to Review via the breadcrumb tab');
  const reviewText = await page.locator('#sv-doc-body').innerText();
  reviewText.includes('Ada Lovelace')
    ? ok('Review shows the name entered on page 1 (shared data model)')
    : bad('shared data did not carry across pages');
  reviewText.includes('ada@example.com')
    ? ok('Review shows the email entered on page 1') : bad('email not carried across pages');
  (await page.evaluate(() => window.__noReload === true))
    ? ok('still no reload after several switches') : bad('a reload happened during navigation');

  // -- Prev goes back, in place ----------------------------------------------
  await page.click('.sv-pagenav-prev');
  await page.waitForSelector('#bio');
  (await page.locator('.sv-page.active').innerText()).includes('Profile')
    ? ok('Prev returned to Profile in place') : bad('Prev did not go back');
  // deep-link: loading a page URL directly lands on that page
  await page.goto(page.url().replace(/\?.*$/, '') + '?view=review');
  await page.waitForSelector('.sv-page.active');
  (await page.locator('.sv-page.active').innerText()).includes('Review')
    ? ok('deep-linking to ?view=review lands on the Review page') : bad('deep-link did not land on Review');

  // ---- 16. Cascading derived fields — one calc field feeds the next ----
  step('16. Cascading derived fields');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Order Calculator")'), '#qty');
  ok('opened the order calculator');
  await page.fill('#qty', '3');
  await page.fill('#price', '20');
  await page.fill('#discount-pct', '10');
  await page.fill('#tax-rate', '8');
  // the calculated fields are :c/labeled-value spans formatted via :fmt "$%.2f"
  const lv = (id) => page.locator(`#lv-${id} span`).innerText();
  const waitLv = (id, want) => page.waitForFunction(
    ([i, w]) => { const el = document.querySelector(`#lv-${i} span`); return el && el.textContent === w; },
    [id, want], { timeout: 6000 });
  // the chain settles: subtotal 60 → discount 6 → tax 4.32 → total 58.32
  await waitLv('total', '$58.32');
  ok('the full chain computed: total = $58.32 (:fmt currency formatting)');
  try {
    await Promise.all([waitLv('subtotal', '$60.00'), waitLv('tax', '$4.32')]);
    ok('intermediate calc fields formatted: subtotal=$60.00, tax=$4.32');
  } catch (e) {
    bad(`intermediate cascade/format values wrong: subtotal=${await lv('subtotal')} tax=${await lv('tax')}`);
  }
  // change ONE upstream input → the whole chain recomputes (and reformats) live
  await page.fill('#qty', '6');
  await waitLv('total', '$116.64');
  ok('editing qty cascaded subtotal→discount→tax→total live (total = $116.64)');
  // crossing the total threshold fires a DOMINO EFFECT → the host shows a notice
  await page.waitForFunction(
    () => { const n = document.querySelector('.sv-notice'); return n && /flagged for review/.test(n.textContent); },
    null, { timeout: 5000 })
    .then(() => ok('a domino effect signalled the host, which raised a notice (total > threshold)'))
    .catch(() => bad('threshold effect did not raise a notice'));

  // ---- 17. Work queues — documents by workflow state, across owners ----
  step('17. Work queues');
  await page.goto(BASE + '/');
  (await page.locator('.sv-topbar a:has-text("Queues")').count()) > 0
    ? ok('the navbar exposes a Queues link') : bad('no Queues link in the navbar');
  // restricting a workflowed form to a role turns it into a team form with a queue
  await page.goto(BASE + '/admin/forms');
  await page.locator('tr:has-text("Support Ticket") input[name=roles]').fill('support');
  await page.locator('tr:has-text("Support Ticket") button:has-text("Save")').click();
  await page.waitForTimeout(300);
  // the queue index lists the workflowed form
  await page.goto(BASE + '/queue');
  (await page.locator('a:has-text("Support Ticket")').count()) > 0
    ? ok('the workflowed form appears in the work queues') : bad('form missing from queues');
  // the per-form queue groups documents by workflow state
  await page.click('a:has-text("Support Ticket")');
  await page.waitForSelector('h1:has-text("Support Ticket queue")');
  (await page.locator('.sv-state').count()) >= 1
    ? ok('the queue groups documents by workflow state') : bad('no state groups in the queue');
  const qOpen = page.locator('table a:has-text("Open")').first();
  (await qOpen.count()) > 0
    ? ok('a queued document is listed with an Open link') : bad('no queued document listed');
  // claim (assign to self) a queued document — routing
  const claimBtn = page.locator('table button:has-text("Claim")').first();
  if (await claimBtn.count()) {
    await claimBtn.click();
    await page.waitForSelector('h1:has-text("Support Ticket queue")');
    (await page.locator('.sv-mine, td:has-text("(you)")').count()) > 0
      ? ok('claimed a document — it shows assigned to me') : bad('claim did not assign the document');
  } else { ok('document already assigned (claim hidden)'); }
  // opening a queued document works (assignee / team-member access)
  await page.locator('table a:has-text("Open")').first().click();
  await page.waitForSelector('h1:has-text("Support Ticket")');
  ok('opened a document from the queue');

  // ---- 18. Cascading / dependent dropdowns -----------------------------
  step('18. Cascading dropdowns');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Clinic Booking")'), '#region');
  ok('opened the clinic booking form');
  // the dependent Clinic select is empty (placeholder only) until a region is set
  (await page.locator('#clinic option').count()) === 1
    ? ok('clinic dropdown empty until a region is chosen') : bad('clinic not empty initially');
  // choosing a region re-renders the clinic select to that region's clinics
  await page.selectOption('#region', 'north');
  await page.waitForFunction(() => {
    const o = [...document.querySelectorAll('#clinic option')].map((e) => e.textContent);
    return o.includes('North General') && !o.includes('South Bay');
  }, null, { timeout: 5000 }).then(() => ok('selecting North narrowed the clinics to the North list'))
    .catch(() => bad('clinic options did not cascade for North'));
  // pick a clinic -> the third-level Department narrows to that clinic
  await page.selectOption('#clinic', 'north-general');
  await page.waitForFunction(() => {
    const o = [...document.querySelectorAll('#department option')].map((e) => e.textContent);
    return o.includes('Cardiology') && o.includes('Orthopedics');
  }, null, { timeout: 5000 }).then(() => ok('picking a clinic narrowed the department (level 3)'))
    .catch(() => bad('department did not cascade from clinic'));
  await page.selectOption('#department', 'ng-cardio');
  // NOW change the TOP of the chain — the whole chain must reset (clinic + department)
  await page.selectOption('#region', 'south');
  await page.waitForFunction(() => {
    const c = [...document.querySelectorAll('#clinic option')].map((e) => e.textContent);
    const d = [...document.querySelectorAll('#department option')].map((e) => e.textContent);
    // clinic re-narrowed to South, department reset to just its placeholder (chain cleared)
    return c.includes('South Bay') && !c.includes('North General') && d.length === 1;
  }, null, { timeout: 5000 })
    .then(() => ok('changing region cascaded to the FULL chain: clinic→South, department reset'))
    .catch(() => bad('full-depth cascade failed (department not reset on region change)'));
  // and the cleared selections are gone
  ((await page.locator('#clinic').inputValue()) === '' && (await page.locator('#department').inputValue()) === '')
    ? ok('stale clinic + department selections were cleared') : bad('stale selections not cleared');

  // ---- 19. Server-side typeahead -------------------------------------
  step('19. Server-side typeahead');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Country Lookup")'), '#country');
  ok('opened the country lookup form');
  // the option list lives on the server — nothing in the browser until you search
  (await page.locator('#search-country li').count()) === 0
    ? ok('no options in the browser until you search') : bad('options present before searching');
  // type a query -> the server filters and morphs in ONLY the matches
  await page.fill('#country', 'can');
  await page.waitForFunction(() => {
    const items = [...document.querySelectorAll('#search-country li')].map((e) => e.textContent);
    return items.includes('Canada') && !items.some((t) => t.includes('Argentina'));
  }, null, { timeout: 5000 })
    .then(() => ok('typing "can" returned only Canada (server-filtered, full list never sent)'))
    .catch(() => bad('server-side search did not filter'));
  // picking a result stores the option VALUE (not the label) and persists it
  await page.click('#search-country button:has-text("Canada")');
  await page.waitForFunction(() => /CA/.test(document.querySelector('.ss-selected')?.innerText || ''),
                             null, { timeout: 4000 })
    .then(() => ok('picking a result stored the option value (CA)'))
    .catch(() => bad('pick did not set the field value'));
  // a different query re-filters server-side
  await page.fill('#country', 'united');
  await page.waitForFunction(() => {
    const items = [...document.querySelectorAll('#search-country li button')].map((e) => e.textContent);
    return items.includes('United Kingdom') && items.includes('United States') && !items.includes('Canada');
  }, null, { timeout: 5000 })
    .then(() => ok('re-searching "united" returned UK + US'))
    .catch(() => bad('server-side re-search failed'));

  // ---- 20. Creation-time field hydration -----------------------------
  step('20. Creation-time hydration');
  await page.goto(BASE + '/');
  await openDoc(page, () => page.click('button:has-text("New Quick Note")'), '#author');
  ok('created a quick note');
  // author is stamped from the signed-in user, status from a literal default
  (await page.locator('#author').inputValue()) === 'Admin'
    ? ok('author hydrated from the session identity (Admin)') : bad(`author not hydrated: ${await page.locator('#author').inputValue()}`);
  (await page.locator('#status').inputValue()) === 'draft'
    ? ok('status hydrated from a literal default (draft)') : bad('status default not applied');
  /^\d{4}-\d{2}-\d{2}$/.test(await page.locator('#created').inputValue())
    ? ok('created hydrated from today’s date') : bad(`created date not hydrated: ${await page.locator('#created').inputValue()}`);

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
