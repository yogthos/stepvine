# Stepvine

<p align="center">
  <img src="stepvine_logo.svg" alt="Stepvine logo" width="420" height="120">
</p>

**Stepvine is a server-authoritative reactive form & app builder.** Forms are
described as data (EDN), their logic runs entirely on the server, and the browser
is a *dumb terminal*: inputs two-way–bind to server-owned signals over
[Datastar](https://data-star.dev) SSE, and computed values, validation, and
workflow state are pushed back as the server recomputes them. There is no
client-side application code to write.

Each app — its EDN document, its workflow, and its CSS — is an **independent app
on top of the platform**, stored in a database and **hot-swappable live**: you can
create, edit, re-style, and re-shape an app from the admin UI with no redeploy.
The platform owns the cross-cutting concerns (auth, access control, persistence,
live multi-user editing, audit, exports); the apps are just content.

It is built on three pieces: the [Domino](https://github.com/domino-clj/domino)
reactive engine (the model + reaction DAG), [Mycelium](https://github.com/yogthos/mycelium)
workflow cells (the request pipelines and the document state machine), and
Datastar (the SSE transport).

## Highlights

- **Forms as data** — a model + reactions/derived fields, validated event/reaction
  functions evaluated in a [SCI](https://github.com/babashka/sci) sandbox (never
  `eval`), collections/tables, and pluggable option/import sources.
- **Live, multi-user** — server-pushed updates, presence, and per-field locking so
  concurrent editors don't clobber each other.
- **Apps in the DB** — app EDN + CSS live in an embedded SQLite store
  (Postgres-ready behind a `FormStore` protocol), with explicit version pinning and
  a sealed archive.
- **Live admin editor** — a split-pane CodeMirror editor (EDN + CSS) with a live
  preview and a per-view picker; create, style, role-gate, and save apps from the UI.
- **Multi-page forms** — a form's views double as ordered pages with breadcrumb
  tabs and prev/next, in-place (no-reload) page switching over Datastar, per-page
  URLs (linkable + deep-linkable), and forward-progress validation gating.
- **Workflows** — declarative per-form state machines (submit/approve/revise, …)
  compiled to Mycelium FSM cells, with finalized documents going read-only.
- **AuthN/Z** — username/password + OAuth2/OIDC, role-based form access, and an
  admin UI for user and role management.
- **Exports** — server-rendered PDF reports.

## Requirements

- **JDK 17+** and the **[Clojure CLI](https://clojure.org/guides/install_clojure)**
  (`clj` / `deps.edn`).
- Optional: **[Babashka](https://babashka.org)** (`bb`) and `make` for task
  shortcuts; **Node.js** for the end-to-end storyboard.

## Build & run

```bash
make run          # or: clj -M:dev   |   bb run
```

`clj -M:dev` starts a REPL with the dev system prepped; type `(go)` to start the
server, then open <http://localhost:3000>. A dev admin is seeded the first time the
user store is empty — **log in with `admin` / `admin`**.

```bash
make test         # unit tests           (clj -M:test  |  bb test)
make uberjar      # build the uberjar     (clj -T:build all  |  bb uberjar)
java -jar target/stepvine-standalone.jar  # run the built jar
make format       # cljstyle fix
```

Or build a container image (multi-stage build, JDK 17):

```bash
docker build -t stepvine . && docker run -p 3000:3000 stepvine
```

### End-to-end storyboards

Headless-browser walkthroughs of the major features:

- `storyboard.mjs` — every feature (auth, live computation, collections, the
  editor, multi-page navigation, workflows, …)
- `document-flow.mjs` — the full document lifecycle on a richer form (create →
  edit → preview chart → generate PDF → submit → list → revise → resubmit)
- `access.mjs` — the admin per-role view-access picker and its enforcement

```bash
cd e2e && npm install && npx playwright install chromium   # one-time

make e2e          # boots a fresh dev server, runs all storyboards, restores data/
                  #   (bb e2e  |  bash e2e/run.sh)
make e2e ARGS="document-flow access"   # a subset (bb e2e document-flow access)
```

`make e2e` is self-contained — it starts `clj -M:dev` on a freshly-seeded store
(so the demo forms reseed from `forms/`), runs the storyboards against it, then
tears the server down and restores your pre-run `data/` (even on failure). To run
a single storyboard by hand instead, start a dev server and `node e2e/storyboard.mjs`.

## Configuration

Set via environment variables (sensible defaults in `resources/system.edn`):

| Variable      | Default        | Purpose                            |
| ------------- | -------------- | ---------------------------------- |
| `PORT`        | `3000`         | HTTP port                          |
| `HTTP_HOST`   | `0.0.0.0`      | Bind address                       |
| `FORMS_DIR`   | `forms`        | App EDN/CSS seeded into the store  |
| `APPS_DB`     | `data/apps.db` | SQLite file for the app store      |
| `REPORTS_DIR` | `data/reports` | Generated PDF reports              |

> The OAuth `:mock` provider is dev/test only and is never exposed in `:prod`.

## Status

Pre-release / actively developed. See `PLAN.md` for the roadmap and `CLAUDE.md`
for contributor/agent conventions. Issues are tracked with
[beads](https://github.com/gastownhall/beads) (`bd`).
