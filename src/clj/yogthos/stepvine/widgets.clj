(ns yogthos.stepvine.widgets
  "Widget library — require this namespace to register all widget render methods.
   Each widget extends the `yogthos.stepvine.render/render-widget` multimethod."
  (:require
   ;; core / structural widgets (form, fields, collections, actions)
   yogthos.stepvine.widgets.form
   yogthos.stepvine.widgets.input-field
   yogthos.stepvine.widgets.value
   yogthos.stepvine.widgets.show
   yogthos.stepvine.widgets.section
   yogthos.stepvine.widgets.action
   yogthos.stepvine.widgets.collection
   ;; field widgets
   yogthos.stepvine.widgets.alert
   yogthos.stepvine.widgets.calendar
   yogthos.stepvine.widgets.chart
   yogthos.stepvine.widgets.checkbox
   yogthos.stepvine.widgets.checkbox-enabled
   yogthos.stepvine.widgets.date-picker
   yogthos.stepvine.widgets.dropdown
   yogthos.stepvine.widgets.input-time
   yogthos.stepvine.widgets.label
   yogthos.stepvine.widgets.menu
   yogthos.stepvine.widgets.radio
   yogthos.stepvine.widgets.selections
   yogthos.stepvine.widgets.slider
   yogthos.stepvine.widgets.table
   yogthos.stepvine.widgets.text
   yogthos.stepvine.widgets.typeahead))
