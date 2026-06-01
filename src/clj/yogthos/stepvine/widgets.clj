(ns yogthos.stepvine.widgets
  "Widget library — require this namespace to register all widget render methods.
   Each widget extends the `yogthos.stepvine.render/render-widget` multimethod.
   Widgets are grouped by category under `yogthos.stepvine.widgets.<group>.*`."
  (:require
   ;; layout / structure
   yogthos.stepvine.widgets.layout.form
   yogthos.stepvine.widgets.layout.section
   yogthos.stepvine.widgets.layout.show
   yogthos.stepvine.widgets.layout.alert
   ;; basic inputs + display
   yogthos.stepvine.widgets.basics.input-field
   yogthos.stepvine.widgets.basics.text
   yogthos.stepvine.widgets.basics.label
   yogthos.stepvine.widgets.basics.value
   yogthos.stepvine.widgets.basics.slider
   yogthos.stepvine.widgets.basics.date-picker
   yogthos.stepvine.widgets.basics.input-time
   ;; selection controls
   yogthos.stepvine.widgets.selection.dropdown
   yogthos.stepvine.widgets.selection.radio
   yogthos.stepvine.widgets.selection.checkbox
   yogthos.stepvine.widgets.selection.checkbox-enabled
   yogthos.stepvine.widgets.selection.selections
   yogthos.stepvine.widgets.selection.menu
   yogthos.stepvine.widgets.selection.typeahead
   ;; tables / collections
   yogthos.stepvine.widgets.tables.collection
   yogthos.stepvine.widgets.tables.table
   ;; buttons / actions
   yogthos.stepvine.widgets.buttons.action
   ;; visualization
   yogthos.stepvine.widgets.viz.chart
   yogthos.stepvine.widgets.viz.calendar))
