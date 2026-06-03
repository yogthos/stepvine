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

(defn form-roles
  "The set of roles a form is restricted to (empty = open to all)."
  [store form-id]
  (get @store (keyword form-id) #{}))

(defn set-form-roles!
  "Restrict `form-id` to `roles` (empty/nil = open to all)."
  [store form-id roles]
  (swap! store assoc (keyword form-id) (set roles)))

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
  (into #{:admin} (mapcat identity (vals @store))))

(defmethod ig/init-key :store/access
  [_ {:keys [file]}]
  (store/edn-file-store file {:init {} :label "form-access roles persisted to"}))
