(ns yogthos.stepvine.users
  "User store (`:store/users`).

   User records: {:id <uuid> :username .. :display-name .. :password-hash ..
   :created-at <ms>}. Passwords are bcrypt-hashed (buddy-hashers); the plaintext
   is never stored. Backed by an atom, or a duratom when a `:file` is configured
   (v1 'database' for users). On startup a dev admin is seeded if the store is
   empty. Swappable to a real DB behind this API."
  (:require
   [buddy.hashers :as hashers]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [duratom.core :as duratom]
   [integrant.core :as ig])
  (:import
   [java.util UUID]))

(defn find-by-username
  [store username]
  (first (filter #(= username (:username %)) (vals @store))))

(defn get-user [store id] (get @store id))

(defn list-users [store] (vals @store))

(defn create!
  "Create a user with a bcrypt-hashed password. Throws if the username is taken.
   Returns the (hash-bearing) record."
  [store {:keys [username password display-name]}]
  (when (find-by-username store username)
    (throw (ex-info "Username already taken" {:username username})))
  (let [user {:id            (str (UUID/randomUUID))
              :username      username
              :display-name  (or display-name username)
              :password-hash (hashers/derive password)
              :created-at    (System/currentTimeMillis)}]
    (swap! store assoc (:id user) user)
    user))

(defn check-password
  "True if `password` matches the user's stored hash."
  [user password]
  (boolean (and user (:valid (hashers/verify password (:password-hash user))))))

;; --- OAuth / OIDC identities (§15.13) -------------------------------------

(defn find-by-oauth
  "The user federated from `[provider subject]`, or nil."
  [store provider subject]
  (first (filter (fn [u] (= {:provider provider :subject subject} (:oauth u)))
                 (vals @store))))

(defn find-or-create-oauth!
  "Find the user for an OIDC profile `{:provider :subject :email :name}`, creating
   a password-less federated account on first sign-in. Returns the user."
  [store {:keys [provider subject email name]}]
  (or (find-by-oauth store provider subject)
      (let [user {:id           (str (UUID/randomUUID))
                  :username     (or email subject)
                  :display-name (or name email subject)
                  :oauth        {:provider provider :subject subject}
                  :created-at   (System/currentTimeMillis)}]
        (swap! store assoc (:id user) user)
        user)))

(defn- seed-admin! [store]
  (when (empty? @store)
    (create! store {:username "admin" :password "admin" :display-name "Admin"})
    (log/info "seeded dev admin user (admin/admin) — change in production")))

(defmethod ig/init-key :store/users
  [_ {:keys [file]}]
  (let [store (if file
                (do (io/make-parents file)
                    (log/info "users persisted to" file)
                    (duratom/duratom :local-file :file-path file :init {}))
                (atom {}))]
    (seed-admin! store)
    store))
