# Vendored front-end assets

All runtime JS/CSS is served locally from `resources/public/vendor/` (mapped to
`/vendor/…`) — **no CDN**. This removes the network dependency that made the
admin-editor storyboard step flaky (CodeMirror was loaded from esm.sh).

## What's vendored

| File (`resources/public/vendor/`) | Source | Used by |
|---|---|---|
| `datastar.js`        | starfederation/datastar @ v1.0.1 | the reactive document view (`form.html`) |
| `codemirror.js`      | bundled here from the `@codemirror/*` packages | the app editor (`web/editor.clj`) |
| `highcharts.js`      | highcharts @ 11 | the `:c/chart` widget |
| `calendarjs.min.js` + `.css` | @calendarjs/ce | the `:c/calendar` widget |
| `lemonade.min.js`    | lemonadejs | calendarjs dependency |

## Rebuilding CodeMirror

`codemirror.js` is the only bundled (multi-package) asset. To rebuild it after a
version bump in `package.json`:

```bash
cd vendor-build
npm install
npm run build      # esbuild -> ../resources/public/vendor/codemirror.js
```

`node_modules/` is gitignored; the built `codemirror.js` is committed. The other
vendor files are single files fetched directly from their upstreams (see the
table) and committed as-is.
