// End-to-end DOCUMENT FLOW storyboard. Drives a real Chromium through the full
// lifecycle of a richer document type (the seeded `project-report` form):
//
//   admin sets up / reviews the app  →  a user logs in  →  creates a document
//   →  edits every kind of field + a milestones table  →  previews the budget
//   chart  →  generates a printable PDF report  →  submits (read-only)  →  finds
//   it in their document list  →  views the submitted version  →  revises, edits,
//   resubmits  →  views again.
//
// Beyond "does it work", this asserts the UX at each step: the right controls are
// present and where they belong, live computation lands, read-only states show,
// and navigation between the edit and preview views is clear.
//
// Run with the dev server on :3000 (the project-report form seeds on boot):
//   BASE=http://localhost:3000 node document-flow.mjs
import { chromium } from 'playwright';

const BASE = process.env.BASE || 'http://localhost:3000';
let failures = 0;
const ok   = (m) => console.log(`  ✓ ${m}`);
const bad  = (m) => { failures++; console.log(`  ✗ ${m}`); };
const step = (n) => console.log(`\n=== ${n} ===`);

const pageErrors = [];
function watch(page, label) {
  page.on('pageerror', (e) => pageErrors.push(`[${label}] ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(`[${label}] console: ${m.text()}`); });
}

// Open a document page the way a real user does: arm the /sse wait before
// navigating (datastar is cached, so the stream can open before a post-hoc
// listener attaches), then wait for it to connect == datastar is live.
async function openDoc(page, navigate, readySel, ms = 6000) {
  const sse = page.waitForRequest((r) => /\/sse/.test(r.url()), { timeout: ms });
  await navigate();
  if (readySel) await page.waitForSelector(readySel);
  await sse;
  await page.waitForTimeout(200); // let the on-connect catch-up sync apply
}

// Blur the focused field so its lock/unlock + value POSTs flush before we
// navigate away (a real user gets this beat for free).
async function settle(page, ms = 500) {
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await page.waitForTimeout(ms);
}

async function signIn(page, username, password) {
  await page.goto(BASE + '/login');
  await page.fill('input[name=username]', username);
  await page.fill('input[name=password]', password);
  await Promise.all([page.waitForLoadState('networkidle'),
                     page.click('button:has-text("Sign in")')]);
}

// Register the lifecycle user, or sign in if they already exist (re-runnable).
async function ensureUser(page, username, displayName, password) {
  await page.goto(BASE + '/register');
  await page.fill('input[name=username]', username);
  await page.fill('input[name=display-name]', displayName);
  await page.fill('input[name=password]', password);
  await page.click('button:has-text("Create account")');
  try {
    await page.waitForURL(BASE + '/', { timeout: 3000 });
    return 'registered';
  } catch {
    await signIn(page, username, password);
    return 'signed-in';
  }
}

const browser = await chromium.launch();

try {
  // ======================================================================
  step('1. Admin sets up / reviews the app (authoring UI)');
  const adminCtx = await browser.newContext();
  const admin = await adminCtx.newPage();
  watch(admin, 'admin');
  await signIn(admin, 'admin', 'admin');
  (await admin.locator('.sv-topbar .sv-brand').isVisible())
    ? ok('admin signed in') : bad('admin sign-in failed');

  await admin.goto(BASE + '/admin/forms');
  (await admin.locator('tr:has-text("Project Report"), li:has-text("Project Report"), :text("Project Report")').count()) > 0
    ? ok('the Project Report app is listed in the admin forms UI')
    : bad('Project Report app not listed in admin UI');

  await admin.goto(BASE + '/admin/forms/project-report/edit');
  await admin.waitForSelector('.cm-editor', { timeout: 20000 });
  ok('live editor (CodeMirror) mounted for the app');
  await admin.waitForTimeout(2000);
  (await admin.frameLocator('#preview').locator('h1:has-text("Project Report")').count()) > 0
    ? ok('live preview renders the form') : bad('live preview did not render the form');
  await adminCtx.close();

  // ======================================================================
  step('2. A user logs in');
  const userCtx = await browser.newContext();
  const page = await userCtx.newPage();
  watch(page, 'user');
  const how = await ensureUser(page, 'reporter', 'Riley Reporter', 'pw');
  ok(`lifecycle user ready (${how})`);
  (await page.locator('.sv-user').innerText()).includes('Riley')
    ? ok('navbar shows the signed-in user') : bad('user not shown in navbar');
  (await page.locator('button:has-text("New Project Report")').count()) > 0
    ? ok('the open-access form offers a "New Project Report" button on the landing')
    : bad('no create button for the Project Report form');

  // ======================================================================
  step('3. Create a document');
  await openDoc(page, () => page.click('button:has-text("New Project Report")'), 'input[data-bind="project"]');
  (await page.locator('h1:has-text("Project Report")').isVisible()) ? ok('document opened on the edit view') : bad('edit view did not open');
  // the key authoring affordances are present and where they belong
  for (const [sel, name] of [
    ['select[data-bind="department"]', 'Department dropdown'],
    ['input[name="status"][value="active"]', 'Status radio group'],
    ['input[data-bind="priority"]', 'Priority slider'],
    ['textarea[data-bind="summary"]', 'Executive summary textarea'],
    ['.widget-table', 'Milestones table'],
    ['.widget-table-add-row', 'Add-milestone button'],
    ['.submit-btn', 'Submit button'],
    ['a:has-text("Preview & report")', 'Preview link']]) {
    (await page.locator(sel).count()) > 0 ? ok(`present: ${name}`) : bad(`MISSING: ${name} (${sel})`);
  }

  // ======================================================================
  step('4. Edit the document');
  await page.fill('input[data-bind="project"]', 'Apollo Migration');
  await page.fill('input[data-bind="lead"]', 'Ada Lovelace');
  await page.selectOption('select[data-bind="department"]', 'engineering');
  await page.fill('input[data-bind="start_date"]', '2026-03-01');
  await page.check('input[name="status"][value="active"]');
  await page.fill('input[data-bind="priority"]', '4');
  await page.fill('textarea[data-bind="summary"]', 'On track for Q2; infra migration underway.');
  await page.check('input[data-bind="on_track"]');
  for (const [q, v] of [['q1', '10'], ['q2', '20'], ['q3', '30'], ['q4', '40']]) {
    await page.fill(`input[data-bind="${q}"]`, v);
  }
  await settle(page);
  // the derived total cascades on the server and lands back in the labelled value
  await page.waitForFunction(() => document.querySelector('#lv-total span')?.textContent.trim() === '100', null, { timeout: 5000 })
    .then(() => ok('live total computed server-side: 100')).catch(async () => bad(`total wrong: ${await page.locator('#lv-total span').innerText()}`));

  // add two milestone rows in the table
  await page.click('.widget-table-add-row'); await page.waitForTimeout(300);
  await page.click('.widget-table-add-row'); await page.waitForTimeout(300);
  const rows = () => page.locator('tbody tr[data-table-row-idx]');
  (await rows().count()) === 2 ? ok('two milestone rows added') : bad(`row count wrong: ${await rows().count()}`);
  const r0 = rows().nth(0);
  await r0.locator('input').nth(0).fill('Cutover plan');
  await r0.locator('input').nth(1).fill('Ada');
  await r0.locator('input').nth(2).fill('8');
  const r1 = rows().nth(1);
  await r1.locator('input').nth(0).fill('Load testing');
  await r1.locator('input').nth(1).fill('Grace');
  await r1.locator('input').nth(2).fill('5');
  await settle(page);
  (await r0.locator('input').nth(0).inputValue()) === 'Cutover plan' ? ok('milestone row values persisted') : bad('milestone values not persisted');

  // ======================================================================
  step('5. Preview the budget chart');
  await settle(page);
  await openDoc(page, () => page.click('a:has-text("Preview & report")'), '.chart-canvas');
  (await page.locator('h1:has-text("Preview")').isVisible()) ? ok('preview view opened') : bad('preview view did not open');
  // read-only summary reflects the edits (with code→label mapping for UX)
  (await page.locator('dd:has-text("Apollo Migration")').count()) > 0 ? ok('summary shows the project name') : bad('project name missing from preview');
  (await page.locator('dd:has-text("Engineering")').count()) > 0 ? ok('department code mapped to label "Engineering"') : bad('department label not shown');
  (await page.locator('dd:has-text("Active")').count()) > 0 ? ok('status code mapped to label "Active"') : bad('status label not shown');
  (await page.locator('dl.sv-summary dd:has-text("100")').count()) > 0 ? ok('total budget shown in summary: 100') : bad('total not in summary');
  // the chart actually renders (Highcharts SVG with the four quarterly columns)
  await page.waitForSelector('.chart-canvas svg', { timeout: 8000 }).then(() => ok('budget chart rendered (Highcharts SVG)')).catch(() => bad('chart SVG did not render'));
  const points = await page.locator('.chart-canvas .highcharts-point').count();
  points >= 4 ? ok(`chart plotted all four quarters (${points} columns)`) : bad(`chart columns wrong: ${points}`);
  (await page.locator('a:has-text("Generate PDF report")').count()) > 0 ? ok('"Generate PDF report" link present in preview') : bad('PDF report link missing');
  (await page.locator('a:has-text("Back to edit")').count()) > 0 ? ok('"Back to edit" link present') : bad('back-to-edit link missing');

  // ======================================================================
  step('6. Generate the PDF report');
  const reportHref = await page.locator('a:has-text("Generate PDF report")').getAttribute('href');
  const report = await userCtx.newPage();
  watch(report, 'report');
  await report.goto(BASE + reportHref);
  (await report.locator('h1:has-text("Apollo Migration")').count()) > 0 ? ok('report renders the project title') : bad('report missing project title');
  // the report must carry the data filled in the form, not just the title
  const rtext = await report.locator('body').innerText();
  rtext.includes('Ada Lovelace') ? ok('report shows the lead (Ada Lovelace)') : bad('report missing the lead value');
  rtext.includes('Engineering') ? ok('report shows the department label (Engineering)') : bad('report missing the department');
  rtext.includes('Active') ? ok('report shows the status label (Active)') : bad('report missing the status');
  (await report.locator('table:has-text("Total")').count()) > 0 ? ok('report includes the quarterly budget table') : bad('report budget table missing');
  (await report.locator('td:has-text("10")').count()) > 0 && (await report.locator('td:has-text("100")').count()) > 0
    ? ok('report budget table shows the quarterly figures + total (10…100)') : bad('report budget table is empty (no figures)');
  (await report.locator('.sv-report-print, button:has-text("Print")').count()) > 0 ? ok('report offers a Print (→ PDF) button') : bad('no Print button on report');
  const pdf = await report.pdf();   // headless print-to-PDF — the actual artifact
  (pdf.subarray(0, 4).toString('latin1') === '%PDF' && pdf.length > 1000)
    ? ok(`printed a valid PDF document (${(pdf.length / 1024 | 0)} KB)`) : bad(`PDF generation failed (${pdf.length} bytes)`);
  await report.close();

  // ======================================================================
  step('7. Submit the document (finalize → read-only)');
  await page.click('.submit-btn');
  await page.waitForTimeout(600);
  (await page.locator('.sv-status').isVisible()) ? ok('read-only banner shown after submit') : bad('no read-only banner after submit');
  (await page.locator('.submit-btn').isVisible()) ? bad('submit button still visible after submit') : ok('submit button hidden once finalized');
  (await page.locator('.revise-btn').isVisible()) ? ok('revise button shown while finalized') : bad('revise button not shown');

  // ======================================================================
  step('8. Find + view the submitted document in the list');
  await page.goto(BASE + '/');
  const docRow = page.locator('.doc', { hasText: 'project-report' }).first();
  (await docRow.count()) > 0 ? ok('the document appears in the user\'s list') : bad('document not in the list');
  (await page.locator('.doc .badge').first().isVisible()) ? ok('the list shows a status badge') : bad('no status badge in the list');
  await openDoc(page, () => docRow.locator('a').first().click(), 'h1');
  (await page.locator('.sv-status').isVisible()) ? ok('reopened document is read-only (submitted)') : bad('submitted doc not read-only');
  // view the submitted version's preview (chart of the finalized numbers)
  await openDoc(page, () => page.click('a:has-text("Preview & report")'), '.chart-canvas');
  await page.waitForSelector('.chart-canvas svg', { timeout: 8000 }).then(() => ok('submitted version: chart renders in preview')).catch(() => bad('submitted preview chart missing'));
  (await page.locator('dl.sv-summary dd:has-text("100")').count()) > 0 ? ok('submitted version shows the finalized total (100)') : bad('submitted total wrong');

  // ======================================================================
  step('9. Revise, edit, resubmit, view again');
  await page.click('.revise-btn');
  await page.waitForTimeout(600);
  (await page.locator('.sv-status').isVisible()) ? bad('still read-only after revise') : ok('revise reopened the document for editing');
  // back to the edit view and change a quarterly figure
  await openDoc(page, () => page.click('a:has-text("Back to edit")'), 'input[data-bind="q4"]');
  await page.fill('input[data-bind="q4"]', '60');   // 10+20+30+60 = 120
  await settle(page);
  await page.waitForFunction(() => document.querySelector('#lv-total span')?.textContent.trim() === '120', null, { timeout: 5000 })
    .then(() => ok('edited total recomputed: 120')).catch(async () => bad(`revised total wrong: ${await page.locator('#lv-total span').innerText()}`));
  await page.click('.submit-btn');
  await page.waitForTimeout(600);
  (await page.locator('.sv-status').isVisible()) ? ok('resubmitted (read-only again)') : bad('resubmit did not finalize');
  await openDoc(page, () => page.click('a:has-text("Preview & report")'), '.chart-canvas');
  (await page.locator('dl.sv-summary dd:has-text("120")').count()) > 0 ? ok('re-viewed version reflects the edit (total 120)') : bad('revised total not reflected in preview');

  await userCtx.close();

  // ======================================================================
  step('Console / page errors');
  if (pageErrors.length === 0) ok('no uncaught page or console errors');
  else { bad(`${pageErrors.length} page/console error(s):`); pageErrors.forEach((e) => console.log('     ' + e)); }

} catch (e) {
  bad(`storyboard threw: ${e.message}`);
  console.log(e.stack);
} finally {
  await browser.close();
}

console.log('\n' + (failures === 0 ? 'ALL STEPS PASSED' : `${failures} FAILURE(S)`));
process.exit(failures === 0 ? 0 : 1);
