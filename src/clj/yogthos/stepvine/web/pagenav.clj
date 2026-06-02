(ns yogthos.stepvine.web.pagenav
  "In-place page switch for multi-page forms: POST /doc/:id/page/:vid renders the
   target view server-side and returns datastar element-morph patches that replace
   the view region (#sv-doc-body) plus the page tabs (#sv-pages) and prev/next
   (#sv-pagenav) — on the *requesting* client only. Which page you're viewing is
   per-user navigation, so it is deliberately NOT broadcast to the other editors
   on the document (unlike field values, which are shared). No full reload: the
   open SSE stream and all field signals stay live across the swap.

   Written as a plain (streaming) ring handler — like web.sse — since a datastar
   patch response is an event-stream, not an html-response. on-open patches and
   returns (no latch), so this is a one-shot response that closes immediately."
  (:require
   [hiccup2.core :as h]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.ring :as ds-ring]
   [yogthos.stepvine.cells.document :as cdoc]
   [yogthos.stepvine.web.layout :as layout]))

(defn switch-handler
  "Build the ring SSE handler for an in-place page switch, closed over the document
   resources (forms, documents, session-manager, options-store, users)."
  [resources]
  (fn [req]
    (let [doc-id  (get-in req [:path-params :id])
          view-id (get-in req [:path-params :vid])
          uid     (get-in req [:session :user-id])]
      (if-let [{:keys [vid html pages]} (cdoc/render-doc-view resources doc-id view-id uid)]
        (let [body (str (h/html [:div#sv-doc-body.sv-doc-body (h/raw html)]))
              tabs (layout/page-tabs-html pages vid doc-id)     ; carries #sv-pages
              nav  (layout/page-prevnext-html pages vid doc-id)] ; carries #sv-pagenav
          (ds-ring/->sse-response
           req
           {ds-ring/on-open
            (fn [gen]
              (d*/patch-elements! gen body)
              (when (seq tabs) (d*/patch-elements! gen tabs))
              (when (seq nav)  (d*/patch-elements! gen nav)))}))
        {:status 404 :body ""}))))
