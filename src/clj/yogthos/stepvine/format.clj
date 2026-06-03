(ns yogthos.stepvine.format
  "Printf-style `:fmt` value formatting for display — both server-side (the initial
   HTML render) and as a live Datastar `data-text` expression evaluated in the
   browser. A widget that accepts `:fmt` (e.g. `:c/value`, `:c/labeled-value`)
   formats with `fmt-value` for the seed and `fmt-text-expr` for the live binding,
   so the two always agree. Pure string/number formatting — no rendering."
  (:require
   [clojure.string :as str]))

(defn fmt-spec
  "Parse a printf-style `:fmt` (e.g. \"%.2f\", \"$%.2f\", \"%.1f kg\", \"%d\") into
   {:whole :pre :type :digits :post}, or nil when there is no recognised
   conversion. `:pre`/`:post` are the literal text around the conversion."
  [fmt]
  (when (string? fmt)
    (when-let [[whole digits type] (re-find #"%[-+ 0]*\.?(\d*)([fds])" fmt)]
      (let [parts (str/split fmt (re-pattern (java.util.regex.Pattern/quote whole)) 2)]
        {:whole whole :pre (or (first parts) "") :digits digits :type type
         :post (or (second parts) "")}))))

(defn- ->num [v]
  (cond (number? v)               v
        (and (string? v) (seq v)) (parse-double v)
        :else                     nil))

(defn fmt-value
  "Apply a printf-style `:fmt` to `v` for server-side display (the initial render).
   Coerces strings to numbers for %f/%d; passes the raw value through on any
   failure; nil/empty render as \"\"."
  [fmt v]
  (if (or (nil? fmt) (nil? v) (= "" v))
    (str (or v ""))
    (if-let [{:keys [type]} (fmt-spec fmt)]
      (try
        (case type
          "f" (if-let [n (->num v)] (format fmt (double n)) (str v))
          "d" (if-let [n (->num v)] (format fmt (long (Math/round (double n)))) (str v))
          (format fmt (str v)))
        (catch Exception _ (str v)))
      (str v))))

(defn fmt-text-expr
  "A datastar `data-text` expression that formats signal `sig` per `:fmt` live in
   the browser (e.g. \"$total\" + \"$%.2f\" -> a '$58.32' expression). Falls back to
   the bare signal when `:fmt` has no recognised conversion."
  [fmt sig]
  (if-let [{:keys [pre digits type post]} (fmt-spec fmt)]
    (let [n    (if (seq digits) digits (case type "f" "2" "0"))
          core (case type
                 "f" (str "Number(" sig ").toFixed(" n ")")
                 "d" (str "Math.round(Number(" sig "))")
                 sig)
          body (str (when (seq pre) (str (pr-str pre) "+")) core
                    (when (seq post) (str "+" (pr-str post))))]
      (str "(" sig "==null||" sig "==='')?'':(" body ")"))
    sig))
