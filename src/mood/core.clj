(ns mood.core
  (:require [clojure.java.io :as io]
            [ring.adapter.jetty :as jetty]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clj-time.format :as f]
            [buddy.hashers :as hashers]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer :all]
            [buddy.core.nonce :as nonce]
            [clojure.java.jdbc :as jdbc]
            [clojure.data.json :as json])
  (:use [ring.middleware.cookies]
        [ring.middleware.content-type]
        [ring.middleware.params]
        [ring.middleware.keyword-params]
        [ring.util.response]
        [clojure.string]
        [selmer.parser]
        [clj-time.core]
        [ring.middleware.json])
  (:gen-class))

(def db
  {:classname   "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname     "mood.sqlite"})

(defn parse-int [s]
  (if (not (nil? s))
    (Integer/parseInt (re-find #"\A-?\d+" s))
    -1))

(defn get-recent-moods [request count]
  (let [params (:params request)
        k (:k params)
        u (:u params)]
    (if (or (nil? u) (nil? k))
      []
      (let [user (:user (first (jdbc/query db ["SELECT user FROM viewkeys WHERE viewkey = ? AND user = ?" k u])))]
        (if (empty? user)
          []
          (let [data (jdbc/query db ["SELECT mood, date FROM mood WHERE user = ? order by date desc limit ?" user count])]
            (clojure.core/reverse data)))))))

(defn create-user [user password]
  (let [hash (hashers/derive password {:alg :scrypt})]
    (jdbc/insert! db :users
                  {:user user
                   :hash hash
                   })))

(defn create-setkey [user]
  (let [setkey (bytes->hex (nonce/random-bytes 16))]
    (jdbc/insert! db :setkeys
                  {:user user
                   :setkey setkey
                   })
    setkey))

(defn create-setkey-handler [request]
  (let [params (:params request)
        user (:user params)
        password (:password params)]
    (cond
      (nil? password)
      "missing paramaters"

      (nil? user)
      "missing parameters"

      :else
      (let [hash (:hash (first (jdbc/query db ["SELECT hash FROM users WHERE user = ?" user])))]
        (cond
          (empty? hash)
          "incorrect password"

          (hashers/check password hash)
          (create-setkey user)

          :else
          "incorrect password")))))

(defn create-viewkey [user]
  (let [viewkey (bytes->hex (nonce/random-bytes 16))]
    (jdbc/insert! db :viewkeys
                  {:user user
                   :viewkey viewkey
                   })
    viewkey))

(defn create-viewkey-handler [request]
  (let [params (:params request)
        user (:user params)
        password (:password params)]
    (cond
      (nil? password)
      "missing paramaters"

      (nil? user)
      "missing parameters"

      :else
      (let [hash (:hash (first (jdbc/query db ["SELECT hash FROM users WHERE user = ?" user])))]
        (cond
          (empty? hash)
          "incorrect password"

          (hashers/check password hash)
          (create-viewkey user)

          :else
          "incorrect password")))))

(defn create-user-handler [request]
  (let [params (:params request)
        user (:user params)
        password (:password params)]
    (cond
      (nil? password)
      "missing paramaters"

      (nil? user)
      "missing parameters"

      :else
      (let [user-in-db (jdbc/query db ["SELECT user FROM users WHERE user = ?" user])]
        (if (not (empty? user-in-db))
          "user already exists!"
          (create-user user password))))))

(defn mood-amend-with-comment [rowid setkey]
  (render-file "comments.html" {:row rowid :k setkey}))

(defn amend-mood [request]
  (let [params (:params request)
        row (parse-int (:row params))
        setkey (:k params)
        user (:user (first (jdbc/query db ["SELECT user FROM setkeys WHERE setkey = ?" setkey])))
        comments (if (nil? (:comments params))
                           ""
                           (trim (:comments params)))]
    (if (and
         (> row 0)
         (not (empty? user)))
      (do
        (jdbc/execute! db ["update mood set comments = ? where rowid = ? and user = ?" comments row user])
        "ok")
      "failed")))

(defn mood [request]
  (if (nil? (:k (:params request)))
    ""
    (let [params (:params request)
          mood (parse-int (:mood params))
          setkey (:k (:params request))
          user (:user (first (jdbc/query db ["SELECT user FROM setkeys WHERE setkey = ?" setkey])))
          comments (if (nil? (:comments params))
                     ""
                     (trim (:comments params)))]
      (if (and (<= mood 10) (>= mood 0))
        (let [rowid (first (vals (first (jdbc/insert! db :mood
                                                      {:mood mood
                                                       :date (c/to-epoch (java.sql.Timestamp. (.getTime (java.util.Date.))))
                                                       :comments comments
                                                       :user user
                                                       }))))]
          (if (nil? (:comments params))
            (mood-amend-with-comment rowid setkey)
            rowid))))))

(defn set-mood []
  (slurp (io/resource "mood.html")))

(defn mood-dialer [request]
  (let [setkey (:k (:params request))]
    (if (nil? setkey)
      {:status 403 :body "missing key"}
      (render-file "vote.html" {:k setkey}))))

(defn chart-js []
  (slurp (io/resource "Chart.js")))

(defn mood-chart [request]
  (let [c (:count (:params request))
        cnt (if (nil? c)
                30
                (parse-int c))
        count (if (= -1 cnt)
                30
                cnt)
        data (get-recent-moods request count)
        moods (json/write-str (for [x data] (:mood x)))
        labels (clojure.string/replace (json/write-str (for [x data] (f/unparse (:date-time f/formatters) (c/from-long (* 1000 (:date x)))))) "\"" "'")]
    (render-file "chart.html" {:data moods :labels labels})))

(defn handler [request]
  (let [uri (:uri request)]
    (case uri
      "/" (response "Mood HQ")
      "/mood" (response (mood request))
      "/chart" (response (mood-chart request))
      "/Chart.js" (response (chart-js))
      "/user/create" (response (create-user-handler request))
      "/viewkey/create" (response (create-viewkey-handler request))
      "/setkey/create" (response (create-setkey-handler request))
      "/mood/set" (response (mood-dialer request))
      "/mood/get" (response (get-recent-moods request (parse-int (:count (:params request)))))
      "/mood/amend" (response (amend-mood request))
      {:status 404})))

(def app
  (-> handler
      (wrap-keyword-params)
      (wrap-cookies)
      (wrap-json-response)
      (wrap-json-params)
      (wrap-params)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (jetty/run-jetty app {:host "localhost" :port 6868}))
