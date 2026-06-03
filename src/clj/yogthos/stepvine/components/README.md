# Widget catalog

Every widget is a `defmethod render-widget :stepvine.components/<name>` (registered
in `../components.clj`). A form references one by its `:c/<name>` keyword (the
alias `{"c" "stepvine.components"}` is declared per view). Widgets are **pure
render** — id/attrs/body → hiccup with Datastar bindings; all state is
server-side. The shared render SPI lives in `../render.clj` (signal names, field
URLs, the recursive walk) and `:fmt` formatting in `../format.clj`.

To see them all in context, open the `showcase.edn` demo form.

| `:c/widget` | namespace | what it is |
|---|---|---|
| **Layout** ||
| `:c/form` | `layout/form` | the form container — seeds all signals + opens the SSE stream |
| `:c/section` · `:c/section-nav` | `layout/section`,`layout/section_nav` | fieldset group · jump-to-section sidebar |
| `:c/grid` | `layout/grid` | responsive N-column grid (`:cols`, per-child `:span`) |
| `:c/h-box` · `:c/v-box` | `layout/box` | flexbox row / column (re-com) |
| `:c/scroller` · `:c/border` · `:c/gap` · `:c/line` | `layout/primitives` | structural primitives |
| `:c/show` | `layout/show` | conditional block (visible when a reaction is truthy) |
| `:c/alert` · `:c/alert-list` | `layout/alert` | one styled message · a stack |
| **Basic inputs / display** ||
| `:c/input-field` | `basics/input_field` | text/number input — two-way bound, locked |
| `:c/input-password` · `:c/input-time` | `basics/input_password`,`basics/input_time` | password · HTML5 time |
| `:c/date-picker` | `basics/date_picker` | date input + relative min/max, quick-set helpers, caption |
| `:c/textarea` · `:c/labeled-value` | `basics/text` | multiline · read-only labeled value |
| `:c/slider` | `basics/slider` | range slider with live value |
| `:c/value` · `:c/label` · `:c/title` · `:c/paragraph` | `basics/*` | signal-bound text / heading / prose (`:fmt` supported) |
| **Selection** ||
| `:c/dropdown` · `:c/dropdown-select` | `selection/dropdown` | `<select>` with options + placeholder + filter |
| `:c/radio` · `:c/checkbox` · `:c/checkbox-enabled` | `selection/*` | radio group · boolean · checkbox-gated field |
| `:c/typeahead` | `selection/typeahead` | HTML5 datalist autocomplete (client list) |
| `:c/search-select` | `selection/search_select` | **server-side** typeahead (list never reaches the browser) |
| `:c/multi-select` · `:c/selection-list` · `:c/selections` | `selection/*` | dual list-box · check list · button set |
| `:c/tree-select` | `selection/tree_select` | hierarchical multi-check tree |
| `:c/menu` | `selection/menu` | button group that sets a value / triggers actions |
| **Buttons / actions** ||
| `:c/submit` · `:c/revise` | `buttons/submit` | finalize / re-open a view (§15.5) |
| `:c/workflow` | `buttons/workflow` | trigger a workflow FSM action |
| `:c/action` · `:c/build-button` | `buttons/action` | server-side action (e.g. export) · build |
| `:c/hyperlink` · `:c/info-button` | `buttons/*` | link · inline-help popover toggle |
| **Overlays** ||
| `:c/modal-panel` · `:c/popover` | `overlays/*` | modal dialog · anchored popover (client UI state) |
| `:c/entry-modal` | `overlays/entry_modal` | modal sub-form: scratch fields → a new collection row |
| **Tables / collections** ||
| `:c/collection` | `tables/collection` | item-template container (+ nested collections) |
| `:c/table` | `tables/table` | full table: per-cell locking, sort/filter/page, DnD, column customization |
| **Display / nav / viz** ||
| `:c/tabs` | `navigation/tabs` | tab selector |
| `:c/progress-bar` · `:c/throbber` | `display/*` | progress · spinner |
| `:c/chart` · `:c/calendar` | `viz/*` | Highcharts chart · calendarjs schedule (assets vendored under `/vendor`) |

**Conventions every widget follows** (see `../render.clj`):
- `data-bind="<signal>"` uses the bare signal **name** (no `$`); `data-text`/
  `data-show`/`data-on` use `$<signal>` expressions.
- Editable fields emit the focus→lock / blur→unlock / input→post trio via
  `render/field-post-url` & friends (item-aware: top-level vs collection vs nested).
- A widget inside a collection item reads `:item {:path …}` from the context;
  `render/item-signal-name` builds the `<coll>_<idx>_…_<field>` signal.
