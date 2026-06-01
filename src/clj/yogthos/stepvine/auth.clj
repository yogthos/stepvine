(ns yogthos.stepvine.auth
  "Authentication service — verifying credentials and reading the current user
   from a request's (signed cookie) session."
  (:require
   [yogthos.stepvine.users :as users]))

(defn authenticate
  "Return the user record if the username/password are valid, else nil."
  [users-store username password]
  (when-let [user (users/find-by-username users-store username)]
    (when (users/check-password user password)
      user)))

(defn current-user-id
  "The authenticated user id from the request session, or nil."
  [request]
  (get-in request [:session :user-id]))

(defn current-user
  "The authenticated user record, or nil."
  [users-store request]
  (some->> (current-user-id request) (users/get-user users-store)))

(defn authenticated?
  [request]
  (some? (current-user-id request)))
