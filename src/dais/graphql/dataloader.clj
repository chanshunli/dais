(ns
  ^{:author "Ziyang Hu"}
  dais.graphql.dataloader
  (:require [clojure.core.async :as a]
            [clojure.set :as set]
            [taoensso.timbre :refer [info debug trace error]]
            [com.walmartlabs.lacinia.executor :as executor]
            [dais.postgres.query-helpers :as h]
            [com.walmartlabs.lacinia.resolve :as resolve])
  (:import (com.walmartlabs.lacinia.resolve ResolverResult)))

(defn make-dataloader
  [conn {:keys [key-fn key-processor]
         :or   {key-fn        :id
                key-processor identity}}]
  (let [dataloader-chan (a/chan 1024)
        put-sentinel #(a/>!! dataloader-chan ::sentinel)
        put-force-sentinel #(a/>!! dataloader-chan ::force-sentinel)
        cache (atom {})]
    (a/thread
      (loop [pending {}
             sentinel? false]
        (if-let [data (a/<!! dataloader-chan)]
          (do
            (trace "dataloader-loop" data)
            (cond
              ;; first case: force sentinel or two sentinels in a row
              ;; flush everything
              (or (= data ::force-sentinel)
                  (and sentinel? (= data ::sentinel)))
              (do
                ;; TODO support vector of keys
                (trace "dataloader pending requests:" (count pending))
                (doseq [[batch-fn vs] pending]
                  (h/with-conn [c conn]
                    (let [ks (set (map first vs))
                          result (try
                                   (batch-fn {:db c} ks)
                                   (catch Exception ex
                                     (error ex)))
                          result (into {}
                                       (for [row result]
                                         [(key-fn row) row]))
                          missing (into {}
                                        (for [k (set/difference ks (set (keys result)))]
                                          [k ::not-found]))]
                      (when (seq result)
                        (trace "dataloader result" result)
                        (swap! cache update batch-fn merge result))
                      (when (seq missing)
                        (trace "dataloader missing values" missing)
                        (swap! cache update batch-fn merge missing))
                      (trace "dataloader cache" @cache)
                      (doseq [[k c] vs]
                        (let [v (get-in @cache [batch-fn k])]
                          (when-not (= v ::not-found)
                            (trace "dataloader putting new value" k v)
                            (a/put! c v)))
                        (a/close! c)))))
                (recur {} false))

              ;; second case: one sentinel
              ;; wait for the next data or sentinel
              (= data ::sentinel)
              (do
                (put-sentinel)
                (recur pending true))

              :else
              (let [{:keys [batch-fn ret-chan key]} data]
                (if (sequential? key)
                  (do
                    ;; TODO support vector of keys
                    (recur
                      (identity pending)
                      false))
                  (if-let [cache-value (get-in @cache [batch-fn (key-processor key)])]
                    ;; third case: incoming request has applicable cache
                    (do
                      (when-not (= cache-value ::not-found)
                        (trace "dataloader putting cached value" key cache-value)
                        (a/put! ret-chan cache-value))
                      (a/close! ret-chan)
                      (put-sentinel)
                      (recur pending false))
                    ;; fourth case: incoming request does not have applicable cache
                    (do
                      (if (> (count (mapcat second pending)) 512)
                        (put-force-sentinel)
                        (put-sentinel))
                      (recur
                        (update pending batch-fn conj [(key-processor key) ret-chan])
                        false)))))))
          ;; dataloader is closed
          (do
            (trace "dataloader cache at closing" @cache)
            (doseq [[batch-fn vs] pending
                    [k c] vs]
              (a/close! c))))))
    dataloader-chan))

(defn batch-loader
  [{:keys [batch value-> arg-> extract-fn]}]
  (trace "Making dataloader with" batch value-> arg->)
  ^ResolverResult ^:graphql/no-wrap
  (fn [{:keys [dataloader] :as ctx} args value]
    (try
      (if-let [key (cond
                     value-> (value-> value)
                     arg-> (arg-> args)
                     extract-fn (extract-fn ctx args value))]
        (let [selections (executor/selections-seq ctx)]
          (trace "Dataloader Batch" key (vec selections))
          (if (and (= 1 (count selections))
                   (= "id" (name (first selections)))
                   (nil? value))
            (resolve/resolve-as {:id key})
            (let [resolve-promise (resolve/resolve-promise)
                  ret-chan (a/promise-chan)
                  payload {:batch-fn batch
                           :ret-chan ret-chan
                           :key      key}]
              (trace "Dataloader is called with payload" payload)
              (a/put! dataloader payload)
              (a/take! ret-chan
                       (fn [value]
                         (trace "Dataloader resolve result" value)
                         (resolve/deliver! resolve-promise value nil)))
              resolve-promise)))
        (resolve/resolve-as nil))
      (catch Throwable ex
        (error ex)
        (resolve/resolve-as nil {:message (str ex)})))))