(ns metabase.db
  "Korma database definition and helper functions for interacting with the database."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [environ.core :refer [env]]
            (korma [core :refer :all]
                   [db :refer :all])
            [metabase.config :refer [app-defaults]]
            [metabase.db.internal :refer :all]
            [metabase.util :as u]))


(declare post-select)

(defonce ^{:doc "Path to our H2 DB file from env var or app config."}
  db-file
  (delay
   (str "file:" (or (:database-file env)
                    (str (System/getProperty "user.dir") "/" (:database-file app-defaults)))
        ";AUTO_SERVER=TRUE;MV_STORE=FALSE")))
;; Tell the DB to open an "AUTO_SERVER" connection so multiple processes can connect to it (e.g. web server + REPL)
;; Do this by appending `;AUTO_SERVER=TRUE` to the JDBC URL (see http://h2database.com/html/features.html#auto_mixed_mode)


(defonce ^{:private true
           :doc "Setup Korma default DB."}
  setup-db
  (memoize
   (fn []
     (log/info (str "Using H2 database file: " @db-file))
     (let [db (create-db (h2 {:db @db-file
                              :naming {:keys str/lower-case
                                       :fields str/upper-case}}))]
       (default-connection db)))))


(defn migrate
  "Migrate the database `:up`, `:down`, or `:print`."
  [direction]
  (setup-db)
  (let [conn (jdbc/get-connection {:subprotocol "h2"
                                   :subname @db-file})]
    (case direction
      :up    (com.metabase.corvus.migrations.LiquibaseMigrations/setupDatabase conn)
      :down  (com.metabase.corvus.migrations.LiquibaseMigrations/teardownDatabase conn)
      :print (let [sql (com.metabase.corvus.migrations.LiquibaseMigrations/genSqlDatabase conn)]
               (log/info (str "Database migrations required\n\n"
                              "NOTICE: Your database requires updates to work with this version of Metabase.  "
                              "Please execute the following sql commands on your database before proceeding.\n\n"
                              sql
                              "\n\n"
                              "Once you're database is updated try running the application again.\n"))))))


(defn setup
  "Do general perparation of database by validating that we can connect.
   Caller can specify if we should run any pending database migrations."
  [auto-migrate]
  ;; TODO - test db connection and throw exception if we have trouble connecting
  (if auto-migrate
    (migrate :up)
    ;; if we are not doing auto migrations then return migration sql for user to run manually
    (migrate :print)))


;;; # UTILITY FUNCTIONS

(def ^:dynamic *log-db-calls*
  "Should we enable DB call logging? (You might want to disable this if we're doing many in parallel)"
  true)


;; ## UPD

(defmulti pre-update
  "Multimethod that is called by `upd` before DB operations happen.
   A good place to set updated values for fields like `updated_at`, or serialize maps into JSON."
  (fn [entity _] entity))

(defmethod pre-update :default [_ obj]
  obj) ; default impl does no modifications to OBJ

(defn upd
  "Wrapper around `korma.core/update` that updates a single row by its id value and
   automatically passes &rest KWARGS to `korma.core/set-fields`.

     (upd User 123 :is_active false) ; updates user with id=123, setting is_active=false

   Returns true if update modified rows, false otherwise."
  [entity entity-id & {:as kwargs}]
  (let [kwargs (->> kwargs
                    (pre-update entity))]
    (-> (update entity (set-fields kwargs) (where {:id entity-id}))
        (> 0))))


;; ## DEL

(defn del
  "Wrapper around `korma.core/delete` that makes it easier to delete a row given a single PK value.
   Returns a `204 (No Content)` response dictionary."
  [entity & {:as kwargs}]
  (delete entity (where kwargs))
  {:status 204
   :body nil})


;; ## SEL

(defmulti post-select
  "Called on the results from a call to `sel`. Default implementation doesn't do anything, but
   you can provide custom implementations to do things like add hydrateable keys or remove sensitive fields."
  (fn [entity _] entity))

;; Default implementation of post-select
(defmethod post-select :default [_ result]
  result)

(defmulti default-fields
  "The default fields that should be used for ENTITY by calls to `sel` if none are specified."
  identity)

(defmethod default-fields :default [_]
  nil) ; by default return nil, which we'll take to mean "everything"

(defmacro sel
  "Wrapper for korma `select` that calls `post-select` on results and provides a few other conveniences.

  ONE-OR-MANY tells `sel` how many objects to fetch and is either `:one` or `:many`.

    (sel :one User :id 1)          -> returns the User (or nil) whose id is 1
    (sel :many OrgPerm :user_id 1) -> returns sequence of OrgPerms whose user_id is 1

  OPTION, if specified, is one of `:field`, `:fields`, or `:id`.

    ;; Only return IDs of objects.
    (sel :one :id User :email \"cam@metabase.com\")  -> 120

    ;; Only return the specified field.
    (sel :many :field [User :first_name])            -> (\"Cam\" \"Sameer\" ...)

    ;; Return map(s) that only contain the specified fields.
    (sel :one :fields [User :id :first_name])        -> ({:id 1 :first_name \"Cam\"}, {:id 2 :first_name \"Sameer\"} ...)

  ENTITY may be either an entity like `User` or a vector like `[entity & field-keys]`.
  If just an entity is passed, `sel` will return `default-fields` for ENTITY.
  Otherwise is a vector is passed `sel` will return the fields specified by FIELD-KEYS.

    (sel :many [OrgPerm :admin :id] :user_id 1) -> return admin and id of OrgPerms whose user_id is 1

  ENTITY may optionally be a fully-qualified string name of an entity; in this case, the symbol's namespace
  will be required and the symbol itself resolved at runtime. This is sometimes neccesary to avoid circular
  dependencies. This is slower, however, due to added runtime overhead.

    (sel :one \"metabase.models.table/Table\" :id 1) ; require/resolve metabase.models.table/Table. then sel Table 1

  FORMS may be either keyword args, which will be added to a korma `where` clause, or other korma
   clauses such as `order`, which are passed directly.

    (sel :many Table :db_id 1)                    -> (select User (where {:id 1}))
    (sel :many Table :db_id 1 (order :name :ASC)) -> (select User (where {:id 1}) (order :name ASC))"
  [one-or-many & args]
  {:arglists ([one-or-many option? entity & forms])
   :pre [(contains? #{:one :many} one-or-many)]}
  (if (= one-or-many :one)
    `(first (sel :many ~@args (limit 1)))
    (let [[option [entity & forms]] (u/optional keyword? args)]
      (case option
        :field  `(let [[entity# field#] ~entity]
                   (map field#
                        (sel :many [entity# field#] ~@forms)))
        :id     `(sel :many :field [~entity :id] ~@forms)
        :fields `(let [[~'_ & fields# :as entity#] ~entity]
                   (map #(select-keys % fields#)
                        (sel :many entity# ~@forms)))
        nil     `(-sel-select ~entity ~@forms)))))

(def ^:dynamic *entity-overrides*
  "The entity passed to `-sel-select` gets merged with this dictionary right before `select` gets called. This lets you override some of the korma
   entity fields like `:transforms` or `:table`, if need be."
  {})

(defmacro -sel-select
  "Internal macro used by `sel` (don't call this directly).
   Generates the korma `select` form."
  [entity & forms]
  (let [forms (sel-apply-kwargs forms)]                                          ; convert kwargs like `:id 1` to korma `where` clause
    `(let [[entity# field-keys#] (destructure-entity ~entity)                    ; pull out field-keys if passed entity vector like `[entity & field-keys]`
           entity# (entity->korma entity#)                                       ; entity## is the actual entity like `metabase.models.user/User` that we can dispatch on
           entity-select-form# (-> entity#                                       ; entity-select-form# is the tweaked version we'll pass to korma `select`
                                   (assoc :fields (or field-keys#
                                                      (default-fields entity#))) ; tell korma which fields to grab. If `field-keys` weren't passed in vector
                                   (merge *entity-overrides*))]                  ; then do a `default-fields` lookup at runtime
       (when *log-db-calls*
         (log/debug "DB CALL: " (:name entity#)
                  (or (:fields entity-select-form#) "*")
                  ~@(mapv (fn [[form & args]]
                            `[~(name form) ~(apply str (interpose " " args))])
                          forms)))
       (->> (select entity-select-form# ~@forms)
            (map (partial post-select entity#))))))                             ; map `post-select` over the results


;; ## INS

(defmulti pre-insert
  "Gets called by `ins` immediately before inserting a new object immediately before the korma `insert` call.
   This provides an opportunity to do things like encode JSON or provide default values for certain fields.

    (pre-insert Query [_ query]
      (let [defaults {:version 1}]
        (merge defaults query))) ; set some default values"
  (fn [entity _] entity))

(defmethod pre-insert :default [_ obj]
  obj)   ; default impl returns object as is

(defn ins
  "Wrapper around `korma.core/insert` that renames the `:scope_identity()` keyword in output to `:id`
   and automatically passes &rest KWARGS to `korma.core/values`.

   Returns newly created object by calling `sel`."
  [entity & {:as kwargs}]
  (let [vals (->> kwargs
                  (pre-insert entity))]
    (let [{:keys [id]} (-> (insert entity (values vals))
                           (clojure.set/rename-keys {(keyword "scope_identity()") :id}))]
      (sel :one entity :id id))))


;; ## EXISTS?

(defmacro exists?
  "Easy way to see if something exists in the db.

    (exists? User :id 100)"
  [entity & {:as kwargs}]
  `(not (empty? (select ~entity
                        (fields [:id])
                        (where ~kwargs)
                        (limit 1)))))

;; ## CASADE-DELETE

(defmulti pre-cascade-delete (fn [entity _]
                               entity))

(defmethod pre-cascade-delete :default [_ instance]
  instance)

(defmacro cascade-delete [entity & kwargs]
  `(let [entity# (entity->korma ~entity)
         instances# (sel :many entity# ~@kwargs)]
     (dorun (map (fn [instance#]
                   (pre-cascade-delete entity# instance#)
                   (del entity# :id (:id instance#)))
                 instances#))))
