(ns yogthos.stepvine.components-test
  "Tests for the widget library. Each widget should produce valid hiccup with
   correct Datastar bindings."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render]
   yogthos.stepvine.components))

(def ^:private base-ctx
  "Minimal render context for unit-testing widgets."
  {:values      {:my-field "test-value" :num-field 42 :flag true}
   :rxns        {:bmi-category "normal"}
   :field-opts  {:my-field {:type :text} :num-field {:type :number}
                 :flag {:type :boolean} :opt-field {:type :select}}
   :options     {:opt-field [{:label "A" :value "a"} {:label "B" :value "b"}]}
   :collections {}
   :aliases     {}
   :doc-id      "test-doc"})

(defn- render-widget-hiccup
  "Render a single widget node to hiccup using the base context."
  ([node] (render-widget-hiccup node base-ctx))
  ([node ctx]
   (render/render-node ctx node)))

(defn- hiccup->html [hiccup]
  (str (h/html hiccup)))

;; --- Alert ----------------------------------------------------------------

(deftest alert-renders-message
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/alert {:label "Hello world" :class "alert-success"}]))]
    (is (str/includes? html "Hello world"))
    (is (str/includes? html "alert-success"))
    (is (str/includes? html "role=\"alert\""))))

(deftest alert-supports-conditional-show
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/alert {:label "Hidden" :when "someFlag"}]))]
    (is (str/includes? html "data-show=\"$someFlag\""))))

;; --- Checkbox -------------------------------------------------------------

(deftest checkbox-renders-with-datastar-bindings
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/checkbox {:id :flag :label "Enable"}]))]
    (is (str/includes? html "type=\"checkbox\""))
    (is (str/includes? html "data-bind=\"flag\""))
    (is (str/includes? html "Enable"))
    (is (str/includes? html "data-on:change"))))

(deftest checkbox-read-only-disabled
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/checkbox {:id :flag :label "Locked" :read-only true}]))]
    (is (str/includes? html "disabled"))))

;; --- Textarea -------------------------------------------------------------

(deftest textarea-renders-with-bindings
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/textarea {:id :my-field :label "Notes"}]))]
    (is (str/includes? html "<textarea"))
    (is (str/includes? html "data-bind=\"my_field\""))
    (is (str/includes? html ">test-value</textarea>"))
    (is (str/includes? html "data-on:input__debounce.300ms"))))

(deftest textarea-respects-rows
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/textarea {:id :my-field :label "Notes" :rows 10}]))]
    (is (str/includes? html "rows=\"10\""))))

;; --- Labeled-value --------------------------------------------------------

(deftest labeled-value-displays-bound-text
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/labeled-value {:id :my-field :label "Value"}]))]
    (is (str/includes? html "data-text=\"$my_field\""))
    (is (str/includes? html ">test-value</span>"))))

;; --- Dropdown -------------------------------------------------------------

(deftest dropdown-renders-options
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/dropdown {:id :opt-field :label "Pick one"}]))]
    (is (str/includes? html "<select"))
    (is (str/includes? html "data-bind=\"opt_field\""))
    (is (str/includes? html "<option value=\"a\""))
    (is (str/includes? html "<option value=\"b\""))))

(deftest dropdown-supports-inline-options
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/dropdown
                {:id :dropdown-field :label "Color"
                 :options [["Red" "red"] ["Blue" "blue"]]}]
               ;; need values for inline options
               (assoc-in base-ctx [:values :dropdown-field] "blue")))]
    (is (str/includes? html "Red"))
    (is (str/includes? html "selected"))
    (is (str/includes? html "value=\"blue\""))))

(deftest dropdown-shows-placeholder
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/dropdown
                {:id :opt-field :label "Pick" :placeholder "Choose one..."}]))]
    (is (str/includes? html "Choose one..."))))

;; --- Radio ----------------------------------------------------------------

(deftest radio-renders-options
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/radio
                {:id :opt-field :label "Pick"
                 :options [["Option A" "a"] ["Option B" "b"]]}]
               (assoc-in base-ctx [:values :opt-field] "a")))]
    (is (str/includes? html "type=\"radio\""))
    (is (str/includes? html "Option A"))
    (is (str/includes? html "Option B"))
    (is (str/includes? html "data-bind=\"opt_field\""))
    (is (str/includes? html "checked"))
    (is (str/includes? html "value=\"a\""))))

;; --- Slider ---------------------------------------------------------------

(deftest slider-renders-with-range-input
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/slider {:id :num-field :label "Volume"
                                             :min 0 :max 100 :step 5}]))]
    (is (str/includes? html "type=\"range\""))
    (is (str/includes? html "min=\"0\""))
    (is (str/includes? html "max=\"100\""))
    (is (str/includes? html "step=\"5\""))
    (is (str/includes? html "data-bind=\"num_field\""))
    (is (str/includes? html "data-text=\"$num_field\""))))

;; --- Date picker ----------------------------------------------------------

(deftest date-picker-renders-date-input
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/date-picker {:id :my-field :label "Date"}]))]
    (is (str/includes? html "type=\"date\""))
    (is (str/includes? html "data-bind=\"my_field\""))
    (is (str/includes? html "data-on:change"))))

(deftest date-picker-respects-min-max
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/date-picker {:id :my-field :label "Date"
                                                  :min "2024-01-01" :max "2024-12-31"}]))]
    (is (str/includes? html "min=\"2024-01-01\""))
    (is (str/includes? html "max=\"2024-12-31\""))))

;; --- Typeahead ------------------------------------------------------------

(deftest typeahead-renders-with-datalist
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/typeahead
                {:id :my-field :label "Search"
                 :options ["apple" "banana" "cherry"]}]))]
    (is (str/includes? html "type=\"text\""))
    (is (str/includes? html "<datalist"))
    (is (str/includes? html "apple"))
    (is (str/includes? html "banana"))
    (is (str/includes? html "data-bind=\"my_field\""))))

;; --- Selections -----------------------------------------------------------

(deftest selections-renders-buttons
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/selections
                {:id :opt-field :label "Choose"
                 :options [["First" "a"] ["Second" "b"]]}]
               (assoc-in base-ctx [:values :opt-field] "a")))]
    (is (str/includes? html "data-on:click"))
    (is (str/includes? html "First"))
    (is (str/includes? html "Second"))
    (is (str/includes? html "selected"))))

;; --- Label ----------------------------------------------------------------

(deftest label-renders-static-text
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/label {:text "Static label"}]))]
    (is (str/includes? html "Static label"))
    (is (str/includes? html "class=\"widget label\""))))

(deftest label-binds-to-signal
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/label {:rxn :bmi-category}]))]
    (is (str/includes? html "data-text=\"$bmi_category\""))
    (is (str/includes? html "normal"))))

;; --- Menu -----------------------------------------------------------------

(deftest menu-renders-buttons
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/menu
                {:id :opt-field :label "Action"
                 :options [["Save" "save"] ["Delete" "delete"]]}]))]
    (is (str/includes? html "Save"))
    (is (str/includes? html "Delete"))
    (is (str/includes? html "data-on:click"))
    (is (str/includes? html "@post("))))

;; --- Checkbox-enabled ------------------------------------------------------

(deftest checkbox-enabled-renders-checkbox-and-input
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/checkbox-enabled
                {:id :my-field :label "Enable" :text-label "Details"}]))]
    (is (str/includes? html "type=\"checkbox\""))
    (is (str/includes? html "type=\"text\""))
    (is (str/includes? html "Details"))
    (is (str/includes? html "Enable"))))

;; --- Input-time ------------------------------------------------------------

(deftest input-time-renders-time-input
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/input-time
                {:id :my-field :label "Start time"}]))]
    (is (str/includes? html "type=\"time\""))
    (is (str/includes? html "data-bind=\"my_field\""))
    (is (str/includes? html "Start time"))))

;; --- Table ----------------------------------------------------------------

(def ^:private table-ctx
  "Context with a collection for table tests."
  (assoc base-ctx
         :collections
         {:people {:order [0 1]
                   :field-opts {:name {:type :text} :age {:type :number}}
                   :items {0 {:name "Alice" :age 30}
                           1 {:name "Bob" :age 25}}}}))

(deftest table-renders-rows
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team"}]
               table-ctx))]
    (is (str/includes? html "Team"))
    (is (str/includes? html "<table"))
    (is (str/includes? html "Alice"))
    (is (str/includes? html "Bob"))))

(deftest table-renders-add-button
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team"}]
               table-ctx))]
    (is (str/includes? html "Add Row"))
    (is (str/includes? html "@post("))))

(deftest table-read-only-hides-add-button
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team" :read-only true}]
               table-ctx))]
    (is (not (str/includes? html "Add Row")))))

(deftest table-with-columns-renders-headers
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team"
                 :columns [{:path :name :label "Name" :sortable? true}
                           {:path :age :label "Age" :sortable? true}]}]
               table-ctx))]
    (is (str/includes? html "Name"))
    (is (str/includes? html "Age"))
    (is (str/includes? html "data-on:click"))))

;; --- Chart ----------------------------------------------------------------

(deftest chart-renders-container-with-script
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/chart
                {:id :my-field :label "BMI Trend"
                 :chart-type :line :x-axis-title "Date" :y-axis-title "BMI"}]))]
    (is (str/includes? html "highcharts"))
    (is (str/includes? html "BMI Trend"))
    (is (str/includes? html "chart-canvas"))))

;; --- Table locking ---------------------------------------------------------

(deftest table-cell-locking-uses-cell-path
  "Per-cell locking: each cell's lock-container-path is the cell path [coll idx field]."
  (let [node (yogthos.stepvine.components.widgets.tables.table/build-cell-node
              {:lock-granularity :cell}
              [:people]
              {:path :name :label "Name"}
              [:people 0]
              false)]
    (is (= :stepvine.components/labeled-value (first node)))
    (is (= [:people 0 :name] (get-in node [1 :lock-container-path])))))

(deftest table-row-locking-uses-row-path
  "Per-row locking: all cells in a row share the row path."
  (let [node (yogthos.stepvine.components.widgets.tables.table/build-cell-node
              {:lock-granularity :row}
              [:people]
              {:path :name :label "Name"}
              [:people 0]
              false)]
    (is (= [:people 0] (get-in node [1 :lock-container-path])))))

(deftest table-table-locking-uses-table-path
  "Per-table locking: all cells share the table path."
  (let [node (yogthos.stepvine.components.widgets.tables.table/build-cell-node
              {:lock-granularity :table}
              [:people]
              {:path :name :label "Name"}
              [:people 0]
              false)]
    (is (= [:people] (get-in node [1 :lock-container-path])))))

(deftest table-cell-locking-defaults-to-cell
  "Default lock-granularity is :cell."
  (let [node (yogthos.stepvine.components.widgets.tables.table/build-cell-node
              {}
              [:people]
              {:path :name :label "Name"}
              [:people 0]
              false)]
    (is (= [:people 0 :name] (get-in node [1 :lock-container-path])))))

;; --- Horizontal table ------------------------------------------------------

(deftest horizontal-table-renders-headers-on-left
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team" :horizontal? true}]
               table-ctx))]
    (is (str/includes? html "Team"))
    (is (str/includes? html "horizontal-table"))
    (is (str/includes? html "Alice"))
    (is (str/includes? html "Bob"))))

(deftest calendar-renders-container-with-script
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/calendar
                {:id :my-field :label "Appointments"}]))]
    (is (str/includes? html "calendarjs"))
    (is (str/includes? html "lemonade"))
    (is (str/includes? html "Appointments"))
    (is (str/includes? html "calendar-canvas"))
    ;; reactive read bridge over the <id>_events signal (no DS global)
    (is (str/includes? html "data-attr:data-events"))
    ;; write-back: onupdate persists to the <id>-events field endpoint like @post
    (is (str/includes? html "/doc/test-doc/field/my-field-events"))
    (is (str/includes? html "SIG='my_field_events'"))
    (is (str/includes? html "datastar-request"))))

;; --- Table DnD and customization --------------------------------------------

(deftest table-emits-row-dnd-js-when-movable
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team" :can-move-rows? true}]
               table-ctx))]
    (is (str/includes? html "dragstart"))
    (is (str/includes? html "data-table-dnd"))
    (is (str/includes? html "data-table-row-idx"))))

(deftest table-emits-column-dnd-js-when-customizable
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team" :customizable? true
                 :columns [{:path :name :label "Name" :reorderable? true}
                           {:path :age :label "Age" :removable? true}]}]
               table-ctx))]
    (is (str/includes? html "data-col-reorderable"))
    (is (str/includes? html "data-col-removable"))
    (is (str/includes? html "table-col-drag-from"))))

(deftest table-restore-column-button-appears-only-when-a-column-is-hidden
  (testing "no hidden columns: no restore button"
    (let [html (hiccup->html
                (render-widget-hiccup
                 [:stepvine.components/table
                  {:id :people :label "Team" :customizable? true}]
                 table-ctx))]
      (is (not (str/includes? html "columns-add")))))
  (testing "a hidden column surfaces the restore button"
    (let [html (hiccup->html
                (render-widget-hiccup
                 [:stepvine.components/table
                  {:id :people :label "Team" :customizable? true}]
                 (assoc-in table-ctx [:view-state :people :cols :hidden] #{:age})))]
      (is (str/includes? html "Restore column"))
      (is (str/includes? html "columns-add")))))

(deftest table-emits-editable-label-when-customizable
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team" :customizable? true
                 :columns [{:path :name :label "Name" :editable-label? true}
                           {:path :age :label "Age"}]}]
               table-ctx))]
    (is (str/includes? html "widget-table-editable-label"))
    (is (str/includes? html "columns-label"))))

(deftest horizontal-table-emits-scroll-js
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team" :horizontal? true}]
               table-ctx))]
    (is (str/includes? html "data-horizontal-scroll"))
    (is (str/includes? html "scrollLeft"))))

;; --- re-com parity: ported components --------------------------------------

(deftest input-password-masks-and-binds
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/input-password {:id :my-field :label "Password"}]))]
    (is (str/includes? html "type=\"password\""))
    (is (str/includes? html "data-bind=\"my_field\""))
    (is (str/includes? html "/field/my-field"))))

(deftest title-uses-level-and-binding
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/title {:text "Heading" :level 3 :underline? true}]))]
    (is (str/includes? html "<h3"))
    (is (str/includes? html "title-underline"))
    (is (str/includes? html "Heading")))
  (testing "signal-bound title carries data-text"
    (let [html (hiccup->html
                (render-widget-hiccup
                 [:stepvine.components/title {:rxn :bmi-category}]))]
      (is (str/includes? html "data-text=\"$bmi_category\"")))))

(deftest paragraph-static-and-body
  (is (str/includes?
       (hiccup->html (render-widget-hiccup [:stepvine.components/paragraph {:text "Body"}]))
       "Body"))
  (testing "with body renders children"
    (is (str/includes?
         (hiccup->html (render-widget-hiccup
                        [:stepvine.components/paragraph {} [:strong "bold"]]))
         "<strong>bold</strong>"))))

(deftest hyperlink-href-and-action
  (testing "href link navigates"
    (let [html (hiccup->html
                (render-widget-hiccup
                 [:stepvine.components/hyperlink {:href "/somewhere" :label "Go"}]))]
      (is (str/includes? html "href=\"/somewhere\""))
      (is (str/includes? html "Go"))))
  (testing "action link posts to the document action endpoint"
    (let [html (hiccup->html
                (render-widget-hiccup
                 [:stepvine.components/hyperlink {:action :summary :label "Export"}]))]
      (is (str/includes? html "/doc/test-doc/action/summary"))
      (is (str/includes? html "@post(")))))

(deftest tabs-selects-and-posts
  (let [ctx  (assoc base-ctx :values {:tab "a"} :field-opts {:tab {:type :string}})
        html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/tabs
                {:id :tab :variant :bar
                 :tabs [["First" "a"] ["Second" "b"]]}]
               ctx))]
    (is (str/includes? html "tabs-bar"))
    (is (str/includes? html "role=\"tablist\""))
    (is (str/includes? html "$tab = "))
    (is (str/includes? html "/doc/test-doc/field/tab"))
    ;; the active tab is marked selected
    (is (str/includes? html "tab-btn selected"))))

(deftest progress-bar-binds-width
  (let [ctx  (assoc base-ctx :values {:pct 42} :field-opts {:pct {:type :number}})
        html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/progress-bar {:id :pct :label "Done"}] ctx))]
    (is (str/includes? html "progress-fill"))
    (is (str/includes? html "width: 42%"))           ; SSR seed
    (is (str/includes? html "data-attr:style"))      ; live update over SSE
    (is (str/includes? html "$pct"))))

(deftest throbber-emits-spinner-structure
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/throbber {:size :small :label "Loading"}]))]
    (is (str/includes? html "throbber-small"))
    (is (= 8 (count (re-seq #"<li>" html))))
    (is (str/includes? html "Loading"))))

(deftest boxes-lay-out-children-with-modifier-classes
  (let [hb (hiccup->html
            (render-widget-hiccup
             [:stepvine.components/h-box {:gap :md :align :center :justify :between}
              [:span "a"] [:span "b"]]))]
    (is (str/includes? hb "h-box"))
    (is (str/includes? hb "gap-md"))
    (is (str/includes? hb "align-center"))
    (is (str/includes? hb "justify-between"))
    (is (str/includes? hb "<span>a</span><span>b</span>")))
  (is (str/includes?
       (hiccup->html (render-widget-hiccup [:stepvine.components/v-box {} [:span "x"]]))
       "v-box")))

;; --- re-com parity: array-bound selection widgets --------------------------

(def ^:private array-ctx
  (assoc base-ctx
         :values     {:skills ["clj"] :tags []}
         :field-opts {:skills {:type :array} :tags {:type :array}}))

(deftest selection-list-checks-current-and-binds-reactively
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/selection-list
                {:id :skills :label "Skills"
                 :options [["Clojure" "clj"] ["Rust" "rust"]]}]
               array-ctx))]
    (is (str/includes? html "role=\"listbox\""))
    ;; reactive selected-state (signals-only broadcast → must be a binding)
    (is (str/includes? html "data-attr:checked"))
    (is (str/includes? html "includes("))
    ;; SSR initial state: clj checked, rust not
    (is (re-find #"checked=\"checked\"[^>]*clj|clj[^>]*checked" html))
    (is (str/includes? html "/doc/test-doc/field/skills"))))

(deftest multi-select-renders-both-columns-with-data-show
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/multi-select
                {:id :skills :label "Pick"
                 :options [["Clojure" "clj"] ["Rust" "rust"]]}]
               array-ctx))]
    (is (str/includes? html "Available"))
    (is (str/includes? html "Selected"))
    (is (str/includes? html "data-show"))
    ;; an item appears in BOTH columns (4 buttons for 2 options)
    (is (= 4 (count (re-seq #"multi-select-item" html))))
    ;; SSR: selected clj hidden in Available column, unselected rust hidden in Selected
    (is (str/includes? html "hidden=\"hidden\""))))

(deftest tree-select-nests-branches-and-leaves
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/tree-select
                {:id :tags :label "Tags"
                 :nodes [{:label "Lang" :open? true
                          :children [{:label "Clojure" :value "clj"}
                                     {:label "Rust" :value "rust"}]}]}]
               array-ctx))]
    (is (str/includes? html "<details open=\"open\""))
    (is (str/includes? html "<summary>Lang</summary>"))
    (is (str/includes? html "tree-leaf"))
    (is (str/includes? html "data-attr:checked"))
    (is (str/includes? html "/doc/test-doc/field/tags"))))

;; --- re-com parity: overlays -----------------------------------------------

(deftest modal-panel-gates-on-local-signal
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/modal-panel
                {:signal "showHelp" :title "Help" :trigger-label "Open"}
                [:p "Body"]]))]
    (is (str/includes? html "aria-modal=\"true\""))
    (is (str/includes? html "data-show=\"$showHelp\""))         ; visibility binding
    (is (str/includes? html "$showHelp = true"))               ; trigger opens
    (is (str/includes? html "$showHelp = false"))              ; backdrop/close shuts
    (is (str/includes? html "evt.stopPropagation()"))          ; inside clicks don't close
    (is (str/includes? html "<p>Body</p>"))))

(deftest popover-toggles-and-positions
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/popover
                {:signal "tip" :trigger-label "?" :position :right :title "Tip"}
                [:span "hint"]]))]
    (is (str/includes? html "popover-right"))
    (is (str/includes? html "data-show=\"$tip\""))
    (is (str/includes? html "$tip = !$tip"))                   ; trigger toggles
    (is (str/includes? html "<span>hint</span>"))))

;; --- re-com parity: layout primitives + feedback ---------------------------

(deftest layout-primitives-emit-structure-classes
  (is (str/includes?
       (hiccup->html (render-widget-hiccup [:stepvine.components/scroller {:size :sm} [:p "x"]]))
       "scroller scroller-sm"))
  (is (str/includes?
       (hiccup->html (render-widget-hiccup [:stepvine.components/border {:rounded? true} [:span "x"]]))
       "border-box border-rounded"))
  (is (str/includes?
       (hiccup->html (render-widget-hiccup [:stepvine.components/gap {:size :lg}]))
       "gap-size-lg"))
  (let [line (hiccup->html (render-widget-hiccup [:stepvine.components/line {:orientation :vertical}]))]
    (is (str/includes? line "line-vertical"))
    (is (str/includes? line "role=\"separator\""))))

(deftest alert-list-renders-each-alert
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/alert-list
                {:label "Notices"
                 :alerts [{:class "alert-warning" :heading "Heads up" :body "Check this."}
                          {:class "alert-success" :body "All good."}]}]))]
    (is (str/includes? html "alert-list-items"))
    (is (str/includes? html "alert-warning"))
    (is (str/includes? html "Heads up"))
    (is (str/includes? html "alert-success"))
    (is (= 2 (count (re-seq #"role=\"alert\"" html))))))

(deftest info-button-toggles-help-popover
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/info-button {:signal "i1" :info "Helpful note."}]))]
    (is (str/includes? html "info-button"))
    (is (str/includes? html "$i1 = !$i1"))
    (is (str/includes? html "data-show=\"$i1\""))
    (is (str/includes? html "Helpful note."))))

;; --- Grid layout (stepvine-9by) -------------------------------------------

(deftest grid-lays-children-in-responsive-columns
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/grid {:cols 3 :gap :md}
                [:stepvine.components/input-field {:id :my-field :label "A"}]
                [:stepvine.components/input-field {:id :num-field :label "B"}]]))]
    (testing "container carries the grid + column-count + gap classes"
      (is (str/includes? html "widget grid"))
      (is (str/includes? html "cols-3"))
      (is (str/includes? html "gap-md")))
    (testing "each child is wrapped in a grid cell"
      (is (= 2 (count (re-seq #"grid-cell" html)))))))

(deftest grid-cell-span-controls-width
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/grid {:cols 2}
                [:stepvine.components/input-field {:id :my-field :label "Wide" :span 2}]
                [:stepvine.components/input-field {:id :num-field :label "Narrow"}]]))]
    (testing ":span becomes a span-N class on the cell"
      (is (str/includes? html "span-2")))
    (testing ":span is stripped from the inner widget (not leaked as a DOM attr)"
      (is (not (str/includes? html "span=\"2\""))))))

;; --- Section navigation (stepvine-9by) ------------------------------------

(deftest section-nav-builds-a-jump-to-section-sidebar
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/section-nav {:title "On this page"}
                [:stepvine.components/section {:title "Personal"}
                 [:stepvine.components/input-field {:id :my-field :label "Name"}]]
                [:stepvine.components/section {:title "Contact"}
                 [:stepvine.components/input-field {:id :num-field :label "Phone"}]]]))]
    (testing "a nav sidebar lists each section title as an anchor link"
      (is (str/includes? html "sv-section-nav"))
      (is (str/includes? html "On this page"))
      (is (str/includes? html "href=\"#sv-sec-0\""))
      (is (str/includes? html "href=\"#sv-sec-1\""))
      (is (str/includes? html "Personal"))
      (is (str/includes? html "Contact")))
    (testing "each section is wrapped in a matching scroll anchor"
      (is (str/includes? html "id=\"sv-sec-0\""))
      (is (str/includes? html "id=\"sv-sec-1\"")))
    (testing "section bodies still render"
      (is (str/includes? html "data-bind=\"my_field\""))
      (is (str/includes? html "data-bind=\"num_field\"")))))

(deftest section-nav-skips-non-section-children-in-the-toc
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/section-nav {}
                [:stepvine.components/paragraph {:text "Intro blurb."}]
                [:stepvine.components/section {:title "Only Section"}
                 [:stepvine.components/input-field {:id :my-field :label "Name"}]]]))]
    (testing "non-section children render in the body but not the TOC"
      (is (str/includes? html "Intro blurb."))
      (is (str/includes? html "href=\"#sv-sec-1\""))   ; the section keeps its absolute index
      (is (not (str/includes? html "href=\"#sv-sec-0\""))))))

;; --- Advanced date-picker (stepvine-a3t) ----------------------------------

(def ^:private dated-ctx (assoc base-ctx :today "2026-06-02"))

(deftest date-picker-keeps-literal-min-max
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/date-picker {:id :dob :label "DOB" :min "2000-01-01" :max "2030-12-31"}]
               dated-ctx))]
    (is (str/includes? html "min=\"2000-01-01\""))
    (is (str/includes? html "max=\"2030-12-31\""))))

(deftest date-picker-resolves-relative-constraints
  (testing ":today resolves to the server's current date"
    (let [html (hiccup->html
                (render-widget-hiccup
                 [:stepvine.components/date-picker {:id :dob :label "DOB" :min :today}] dated-ctx))]
      (is (str/includes? html "min=\"2026-06-02\""))))
  (testing "a relative offset map resolves against today (days/weeks/months)"
    (let [html (hiccup->html
                (render-widget-hiccup
                 [:stepvine.components/date-picker
                  {:id :dob :label "DOB" :min {:weeks -2} :max {:days 30}}] dated-ctx))]
      (is (str/includes? html "min=\"2026-05-19\""))   ; today - 14 days
      (is (str/includes? html "max=\"2026-07-02\"")))))  ; today + 30 days

(deftest date-picker-supports-step
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/date-picker {:id :dob :label "DOB" :step 7}] dated-ctx))]
    (is (str/includes? html "step=\"7\""))))

(deftest date-picker-renders-quick-set-helpers
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/date-picker
                {:id :dob :label "DOB"
                 :helpers [{:label "Today" :date :today}
                           {:label "In a week" :date {:weeks 1}}]}] dated-ctx))]
    (testing "each helper is a button that sets the field to a resolved ISO date and posts"
      ;; single quotes render HTML-escaped (&apos;); the browser unescapes at runtime
      (is (str/includes? html "date-helper"))
      (is (str/includes? html "Today"))
      (is (str/includes? html "$dob = &apos;2026-06-02&apos;"))
      (is (str/includes? html "In a week"))
      (is (str/includes? html "$dob = &apos;2026-06-09&apos;"))
      (is (str/includes? html "/field/dob")))))

(deftest date-picker-caption-shows-friendly-display-format
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/date-picker {:id :dob :label "DOB" :caption true}] dated-ctx))]
    (testing "a caption echoes the bound value reformatted for display"
      (is (str/includes? html "date-caption"))
      (is (str/includes? html "toLocaleDateString"))
      (is (str/includes? html "$dob")))))

(deftest date-picker-read-only-omits-helpers-and-posts
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/date-picker
                {:id :dob :label "DOB" :read-only true
                 :helpers [{:label "Today" :date :today}]}] dated-ctx))]
    (is (str/includes? html "readonly"))
    (is (not (str/includes? html "date-helper")))
    (is (not (str/includes? html "@post")))))

;; --- Modal data-entry sub-form (stepvine-ugx) -----------------------------

(deftest entry-modal-renders-scratch-form-and-commit-trigger
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/entry-modal
                {:coll :lines :signal "addLine" :title "Add line"
                 :trigger-label "+ Add line" :add-label "Add"
                 :fields {:item :new-item :qty :new-qty}}
                [:stepvine.components/input-field {:id :new-item :label "Item"}]
                [:stepvine.components/input-field {:id :new-qty  :label "Qty"}]]))]
    (testing "a trigger button opens the modal (client-only UI signal)"
      (is (str/includes? html "modal-trigger"))
      (is (str/includes? html "+ Add line"))
      (is (str/includes? html "$addLine = true")))
    (testing "the scratch fields render, bound to the temp signals"
      (is (str/includes? html "data-bind=\"new_item\""))
      (is (str/includes? html "data-bind=\"new_qty\"")))
    (testing "the Add button commits to the add-from endpoint with the field mapping"
      (is (str/includes? html "modal-add"))
      (is (str/includes? html "/doc/test-doc/coll/lines/add-from?modal=addLine"))
      ;; & is HTML-escaped in the attribute; the mapping itself is intact
      (is (str/includes? html "fields=item:new-item,qty:new-qty")))
    (testing "Add also closes the modal locally for instant feedback"
      (is (str/includes? html "$addLine = false")))))

(deftest entry-modal-defaults-the-open-signal
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/entry-modal
                {:coll :lines :title "Add" :fields {:item :new-item}}
                [:stepvine.components/input-field {:id :new-item :label "Item"}]]))]
    (is (str/includes? html "$entryOpen = true"))
    (is (str/includes? html "$entryOpen = false"))))
