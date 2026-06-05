(ns yogthos.stepvine.access
  "Role-based form access (`:store/access`).

   Users carry `:roles` (managed in the admin UI); forms are associated with roles
   in this store — `{form-id #{roles}}`. A user may use a form when they are an
   **admin**, the form has **no roles** assigned (open to all authenticated
   users), or their roles **intersect** the form's. Decoupled from the immutable
   form versions, so an admin can re-scope access without cutting a new version.

   Backed by a duratom on disk (admin-managed content lives in storage, never in
   system.edn), or a plain atom for tests."
  (:require
   [clojure.set :as set]
   [integrant.core :as ig]
   [yogthos.stepvine.store :as store]
   [yogthos.stepvine.users :as users]))

(defn store [] (atom {}))

(defn- normalize
  "A stored form-access value as the canonical `{role #{view-ids}}` map. A legacy
   value — a plain set of roles — becomes `{role #{}}` (each role → all views).
   `#{}` for a role means *all* of the form's views (a pure form-level grant)."
  [v]
  (cond
    (map? v)  v
    (coll? v) (zipmap (map keyword v) (repeat #{}))
    :else     {}))

(defn form-access
  "The `{role #{view-ids}}` access map for a form (empty = open to all signed-in
   users). A role mapped to `#{}` may see every view; otherwise only the listed
   views. Admins always see everything regardless of this map."
  [store form-id]
  (normalize (get @store (keyword form-id))))

(defn form-roles
  "The set of roles a form is restricted to (empty = open to all)."
  [store form-id]
  (set (keys (form-access store form-id))))

(defn set-form-roles!
  "Restrict `form-id` to `roles`, each granted ALL views (empty/nil = open to
   all). Use `set-form-access!` for per-role view scoping."
  [store form-id roles]
  (swap! store assoc (keyword form-id) (zipmap (map keyword roles) (repeat #{}))))

(defn set-form-access!
  "Restrict `form-id` to a `{role #{view-ids}}` map: each role and the views it may
   access (`#{}` = all views). Empty/nil = open to all signed-in users."
  [store form-id role->views]
  (swap! store assoc (keyword form-id)
         (into {} (map (fn [[r vs]] [(keyword r) (into #{} (map keyword) vs)]))
               role->views)))

(defn view-permitted?
  "May `user` see `view-id` of `form-id`? True when the user is an admin, the form
   is open (no roles), or the user holds a role whose view set includes `view-id`
   (or is empty = all views)."
  [store user form-id view-id]
  (let [acc (form-access store form-id)
        v   (keyword view-id)]
    (boolean (or (users/admin? user)
                 (empty? acc)
                 (some (fn [r] (let [vs (get acc r)]
                                 (and vs (or (empty? vs) (contains? vs v)))))
                       (users/roles user))))))

(defn can-access?
  "True if `user` may use `form-id`."
  [store user form-id]
  (let [fr (form-roles store form-id)]
    (boolean (or (users/admin? user)
                 (empty? fr)
                 (seq (set/intersection (users/roles user) fr))))))

(defn team-member?
  "True if `user` is a workflow handler for `form-id`. A *team* exists only for a
   role-restricted form — assigning roles opts the form into team visibility — and
   its members are the role holders plus admins. An *open* form (no roles) has no
   team, so its documents stay owner-private (owner/shared only). Team members may
   open ANY of the form's documents — the work-queue access rule."
  [store user form-id]
  (let [fr (form-roles store form-id)]
    (boolean (and (seq fr)
                  (or (users/admin? user)
                      (seq (set/intersection (users/roles user) fr)))))))

(defn role-permitted?
  "Granular permission check (per-field/per-view): `user` may act under `roles`
   when they're empty (open), the user is an admin, or the user holds one of them."
  [user roles]
  (boolean (or (empty? roles)
               (users/admin? user)
               (seq (set/intersection (users/roles user) (set roles))))))

(defn accessible-forms
  "The subset of `form-ids` `user` may use, order preserved."
  [store user form-ids]
  (filter #(can-access? store user %) form-ids))

(defn known-roles
  "All roles in use (assigned to any form) plus the built-in :admin — the admin
   UI's role picker."
  [store]
  (into #{:admin} (mapcat (comp keys normalize) (vals @store))))

(defmethod ig/init-key :store/access
  [_ {:keys [file]}]
  (store/edn-file-store file {:init {} :label "form-access roles persisted to"}))
