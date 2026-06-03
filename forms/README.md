# Demo forms — the worked examples

Each `*.edn` here is a runnable Stepvine app that **exercises one feature**. They
are the canonical examples: to learn (or change) a feature, read its demo form
alongside the code it drives (see the feature map in `../ARCHITECTURE.md`). A form
is loaded by id; on a fresh DB the disk files seed the store, after which apps live
in the DB and are editable live at `/admin/forms`.

| Form | App | Feature it demonstrates |
|---|---|---|
| `bmi.edn` | BMI Calculator | reactive derived fields (the minimal Domino calc) |
| `order.edn` | Order Calculator | cascading derived fields + a Domino `:effect` → notice |
| `booking.edn` | Clinic Booking | cascading / dependent dropdowns (region→clinic→department) |
| `intake.edn` | Patient Intake | the 3 data-relationship kinds: imports, DB-sourced options, cross-field validation |
| `event.edn` | Event Booking | cross-field declarative validation (`:before`/`:after`, confirm-equals) |
| `note.edn` | Quick Note | creation-time hydration (`:hydrate` stamps fields on create) |
| `lookup.edn` | Country Lookup | server-side typeahead (the list never reaches the browser) |
| `roster.edn` | Team Roster | a collection (Domino subcontext) with per-item derived fields |
| `tasks.edn` | Task List | the table widget: sort / page / reorder / column filter |
| `ledger.edn` | Ledger | column customization: reorder / hide / restore / relabel |
| `teams.edn` | Teams | nested collections (a collection inside a collection item) |
| `invoice.edn` | Invoice | modal data-entry sub-form (temp fields → a new row) |
| `onboarding.edn`| Onboarding | multi-page form (one model, several `:views`) |
| `profile.edn` | Member Profile | responsive grid layout + section-nav |
| `appointment.edn`| Book Appointment | advanced date-picker (relative min/max, helpers, caption) |
| `demographics.edn`| Patient Demographics | terminology / value-set-bound coded fields (FHIR) |
| `review.edn` | Submission Review | granular per-field permissions (`:write-roles`/`:writable-in`) |
| `triage.edn` | Triage | per-view permissions (`:roles` gate a whole view) |
| `case.edn` | Case | role/state-specific view auto-selection |
| `ticket.edn` | Support Ticket | the workflow FSM + multi-step actions (email/pdf/http) + retry/compensate + `$rev` |
| `builder.edn` | Form builder | the visual form builder (itself a Stepvine form) |
| `showcase.edn` | Widget Showcase | one document exercising **every** widget in the library |

> Widget reference: `../components/README.md` maps each `:c/<widget>` to its
> namespace and option keys. `showcase.edn` shows them all in context.
