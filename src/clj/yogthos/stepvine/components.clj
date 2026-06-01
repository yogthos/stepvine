(ns yogthos.stepvine.components
  "Component library — require this namespace to register every render method.
   Each component extends the `yogthos.stepvine.render/render-widget` multimethod.

   Two tiers under `yogthos.stepvine.components.*`:
     - layout.*          structural containers (form, section, show, alert, box,
                         primitives)
     - widgets.<group>.* interactive controls grouped by category (basics,
                         selection, navigation, tables, buttons, display,
                         overlays, viz)."
  (:require
   ;; layout / structure
   yogthos.stepvine.components.layout.form
   yogthos.stepvine.components.layout.section
   yogthos.stepvine.components.layout.show
   yogthos.stepvine.components.layout.alert
   yogthos.stepvine.components.layout.box
   yogthos.stepvine.components.layout.primitives
   ;; basic inputs + display
   yogthos.stepvine.components.widgets.basics.input-field
   yogthos.stepvine.components.widgets.basics.input-password
   yogthos.stepvine.components.widgets.basics.text
   yogthos.stepvine.components.widgets.basics.label
   yogthos.stepvine.components.widgets.basics.title
   yogthos.stepvine.components.widgets.basics.paragraph
   yogthos.stepvine.components.widgets.basics.value
   yogthos.stepvine.components.widgets.basics.slider
   yogthos.stepvine.components.widgets.basics.date-picker
   yogthos.stepvine.components.widgets.basics.input-time
   ;; selection controls
   yogthos.stepvine.components.widgets.selection.dropdown
   yogthos.stepvine.components.widgets.selection.radio
   yogthos.stepvine.components.widgets.selection.checkbox
   yogthos.stepvine.components.widgets.selection.checkbox-enabled
   yogthos.stepvine.components.widgets.selection.selections
   yogthos.stepvine.components.widgets.selection.selection-list
   yogthos.stepvine.components.widgets.selection.multi-select
   yogthos.stepvine.components.widgets.selection.tree-select
   yogthos.stepvine.components.widgets.selection.menu
   yogthos.stepvine.components.widgets.selection.typeahead
   ;; navigation
   yogthos.stepvine.components.widgets.navigation.tabs
   ;; overlays
   yogthos.stepvine.components.widgets.overlays.modal-panel
   yogthos.stepvine.components.widgets.overlays.popover
   ;; tables / collections
   yogthos.stepvine.components.widgets.tables.collection
   yogthos.stepvine.components.widgets.tables.table
   ;; buttons / actions
   yogthos.stepvine.components.widgets.buttons.action
   yogthos.stepvine.components.widgets.buttons.hyperlink
   yogthos.stepvine.components.widgets.buttons.info-button
   ;; display / feedback
   yogthos.stepvine.components.widgets.display.progress-bar
   yogthos.stepvine.components.widgets.display.throbber
   ;; visualization
   yogthos.stepvine.components.widgets.viz.chart
   yogthos.stepvine.components.widgets.viz.calendar))
