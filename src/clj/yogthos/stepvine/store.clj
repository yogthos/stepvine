(ns yogthos.stepvine.store
  "Shared persistence mechanics for the `:store/*` components.

   Every store is one of two shapes, and each shape was copy-pasted across several
   namespaces before this:

   - **EDN-file / in-memory** (`users`, `access`, `audit`, the `documents` default):
     an atom for tests, or a `duratom` over a local EDN file when one is
     configured. `edn-file-store` is that init.
   - **SQLite** (`documents`, `forms` in `:backend :sql`): a JDBC datasource over an
     embedded SQLite file with its schema created on first use, and rows whose
     payload column is EDN text. `sqlite-datasource` builds the datasource + runs
     the DDL; `read-edn`/`write-edn` are the column codec (EDN, not JSON — the
     payloads carry Clojure data, incl. quoted code, that JSON can't represent)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [duratom.core :as duratom]
   [next.jdbc :as jdbc]))

;; --- EDN-file / in-memory backend -----------------------------------------

(defn edn-file-store
  "The store atom for a `:store/*` component: a `duratom` over `file` (created with
   parent dirs) when one is configured, else a plain in-memory atom. `:init` is the
   empty value ({} for maps, [] for logs); `:label` is the \"… persisted to\" log
   prefix used when a file is configured."
  [file {:keys [init label]}]
  (if file
    (do (io/make-parents file)
        (when label (log/info label file))
        (duratom/duratom :local-file :file-path file :init init))
    (atom init)))

;; --- SQLite backend -------------------------------------------------------

(defn sqlite-datasource
  "A JDBC datasource over an embedded SQLite file at `db-file` (parent dirs
   created), with `schema` (a seq of `create table …`/`create index …` DDL
   strings) executed so the tables exist on first use."
  [db-file schema]
  (io/make-parents db-file)
  (let [ds (jdbc/get-datasource (str "jdbc:sqlite:" db-file))]
    (doseq [stmt schema] (jdbc/execute! ds [stmt]))
    ds))

;; --- EDN column codec -----------------------------------------------------

(defn read-edn
  "Decode an EDN payload column (nil-safe)."
  [s]
  (some-> s edn/read-string))

(defn write-edn
  "Encode a value as an EDN payload column."
  [x]
  (pr-str x))
