(ns yogthos.stepvine.widgets-test
  "Tests for the widget library. Each widget should produce valid hiccup with
   correct Datastar bindings."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render]
   yogthos.stepvine.widgets))

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
  (let [node (yogthos.stepvine.widgets.tables.table/build-cell-node
              {:lock-granularity :cell}
              [:people]
              {:path :name :label "Name"}
              [:people 0]
              false)]
    (is (= :stepvine.components/labeled-value (first node)))
    (is (= [:people 0 :name] (get-in node [1 :lock-container-path])))))

(deftest table-row-locking-uses-row-path
  "Per-row locking: all cells in a row share the row path."
  (let [node (yogthos.stepvine.widgets.tables.table/build-cell-node
              {:lock-granularity :row}
              [:people]
              {:path :name :label "Name"}
              [:people 0]
              false)]
    (is (= [:people 0] (get-in node [1 :lock-container-path])))))

(deftest table-table-locking-uses-table-path
  "Per-table locking: all cells share the table path."
  (let [node (yogthos.stepvine.widgets.tables.table/build-cell-node
              {:lock-granularity :table}
              [:people]
              {:path :name :label "Name"}
              [:people 0]
              false)]
    (is (= [:people] (get-in node [1 :lock-container-path])))))

(deftest table-cell-locking-defaults-to-cell
  "Default lock-granularity is :cell."
  (let [node (yogthos.stepvine.widgets.tables.table/build-cell-node
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
    (is (str/includes? html "calendar-canvas"))))

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

(deftest table-emits-add-column-button-when-customizable
  (let [html (hiccup->html
              (render-widget-hiccup
               [:stepvine.components/table
                {:id :people :label "Team" :customizable? true}]
               table-ctx))]
    (is (str/includes? html "Add Column"))
    (is (str/includes? html "columns-add"))))

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
