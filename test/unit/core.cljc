(ns unit.core
  #?@(:clj  [
             (:require
               [clojure.test :refer :all]
               [unit.utils :as u]
               [carry.core :as carry]
               )]
      :cljs [(:require
               [cljs.test :refer-macros [deftest is testing]]
               [carry.core :as carry])
             ])
  #?(:cljs
     (:require-macros [unit.utils :as u])))

(defn is-read-only-reference
  [a]
  (assert (map? @a) "self test")
  (assert (not= @a {:val :new-value}) "self test")

  (u/is-exception-thrown
    java.lang.ClassCastException #"cannot be cast to clojure.lang.IAtom$"
    js/Error #"^No protocol method ISwap.-swap"
    (swap! a assoc :val :new-value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; App
(deftest
  app-model-is-read-only-reference
  (let [spec {:initial-model {:val 100}
              :control       (constantly nil)
              :reconcile     (constantly nil)}
        app (carry/app spec)]
    (is (= {:val 100} @(:model app)))
    (is-read-only-reference (:model app))))

(deftest
  controller-receives-read-only-model-reference
  (let [spec {:initial-model {:val 100}
              :control       (fn control [model signal _dispatch-signal _dispatch-action]
                               (if (= signal :on-test-model)
                                 (do
                                   (is (= {:val 100} @model))
                                   (is-read-only-reference model))

                                 (is nil "unexpected signal")))
              :reconcile     (constantly nil)}
        app (carry/app spec)]
    ((:dispatch-signal app) :on-test-model)))

(deftest
  action-dispatched-from-controller-updates-model-using-reconciler
  (let [spec {:initial-model {:val 100}
              :control       (fn control [_model signal _dispatch-signal dispatch-action]
                               (if (= signal :on-update-value)
                                 (dispatch-action :update-value)
                                 (is nil "unexpected signal")))
              :reconcile     (fn reconcile [model action]
                               (if (= action :update-value)
                                 (update model :val inc)
                                 (is nil "unexpected action")))}
        app (carry/app spec)]
    ; act
    ((:dispatch-signal app) :on-update-value)

    ; assert
    (is (= {:val 101} @(:model app)))

    ; and again, just in case...
    ; act
    ((:dispatch-signal app) :on-update-value)

    ; assert
    (is (= {:val 102} @(:model app)))

    ; and just in case
    (testing "model is still read-only"
      (is-read-only-reference (:model app)))))

(deftest
  controller-can-dispatch-new-signals
  (let [spec {:initial-model {:val 100}
              :control       (fn control [_model signal dispatch-signal dispatch-action]
                               (condp = signal
                                 :on-dispatch-new-signal (dispatch-signal :on-new-signal)
                                 :on-new-signal (dispatch-action :update-value)))
              :reconcile     (fn reconcile [model action]
                               (if (= action :update-value)
                                 (update model :val inc)
                                 (is nil "unexpected action")))}
        app (carry/app spec)]
    ; act
    ((:dispatch-signal app) :on-dispatch-new-signal)

    ; assert
    (is (= {:val 101} @(:model app)))))

(deftest
  dispatch-signal-returns-nil
  (let [spec {:initial-model {:val 100}
              :control       (constantly :control-return-value)
              :reconcile     (constantly :reconcile-return-value)}
        app (carry/app spec)]
    (is (nil? ((:dispatch-signal app) :some-signal)))))

(deftest
  dispatch-action-returns-nil
  (let [spec {:initial-model {:val 100}
              :control       (fn control [_model _signal _dispatch-signal dispatch-action]
                               (is (nil? (dispatch-action :some-action))))
              :reconcile     (constantly :reconcile-return-value)}
        app (carry/app spec)]
    ((:dispatch-signal app) :some-signal)))

(deftest
  actions-can-be-dispatched-from-model-watch
  (let [spec {:initial-model {:val 100}
              :control       (fn control [model signal _dispatch-signal dispatch-action]
                               (condp = signal
                                 :on-start
                                 (add-watch model :dispatch-action-watch
                                            (fn dispatch-action-watch
                                              [_key _ref old-state new-state]
                                              (when (and (not= old-state new-state)
                                                         (= new-state {:val 101}))
                                                (dispatch-action :update-value))))

                                 :on-update-value
                                 (dispatch-action :update-value)))
              :reconcile     (fn reconcile [model action]
                               (if (= action :update-value)
                                 (update model :val inc)
                                 (is nil "unexpected action")))}
        app (carry/app spec)]
    ; act
    ((:dispatch-signal app) :on-start)
    ((:dispatch-signal app) :on-update-value)

    ; assert
    (is (= {:val 102} @(:model app)))

    ; and just in case
    (testing "model is still read-only"
      (is-read-only-reference (:model app)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Entangle
(deftest
  entangled-reference-is-read-only
  (let [source-atom (atom {:val 100})
        entangled-atom (carry/entangle source-atom identity)]
    (is-read-only-reference entangled-atom)))

(deftest
  entangled-reference-reacts-to-source-atom-changes-using-specified-function
  (let [source-atom (atom {:val 100})
        entangled-ref (carry/entangle source-atom #(update % :val inc))]
    (is (= {:val 101} @entangled-ref))

    (swap! source-atom assoc :val 200)
    (is (= {:val 201} @entangled-ref))

    ; just in case:
    (is-read-only-reference entangled-ref)))

(deftest
  entangled-reference-is-watchable
  (let [source-atom (atom {:val 100})
        entangled-ref (carry/entangle source-atom #(update % :val inc))
        watch1-old-state (atom nil)
        watch1-new-state (atom nil)
        watch2-old-state (atom nil)
        watch2-new-state (atom nil)]
    (is (= entangled-ref (add-watch entangled-ref
                                    :watch1
                                    (fn [key ref os ns]
                                      (is (= entangled-ref ref))
                                      (is (= :watch1 key))
                                      (reset! watch1-old-state os)
                                      (reset! watch1-new-state ns)))))

    (is (= entangled-ref (add-watch entangled-ref
                                    :watch2
                                    (fn [key ref os ns]
                                      (is (= entangled-ref ref))
                                      (is (= :watch2 key))
                                      (reset! watch2-old-state os)
                                      (reset! watch2-new-state ns)))))

    (is (= {:val 101} @entangled-ref))

    ; act
    (swap! source-atom assoc :val 200)

    ; assert
    (is (= {:val 101} @watch1-old-state))
    (is (= {:val 201} @watch1-new-state))
    (is (= {:val 101} @watch2-old-state))
    (is (= {:val 201} @watch2-new-state))

    ; act
    (is (= entangled-ref (remove-watch entangled-ref :watch1)))
    (swap! source-atom assoc :val 300)

    ; assert
    (is (= {:val 101} @watch1-old-state))
    (is (= {:val 201} @watch1-new-state))
    (is (= {:val 201} @watch2-old-state))
    (is (= {:val 301} @watch2-new-state))))

(deftest entangled-reference-can-be-printed
  (let [source-atom (atom {:val 100})
        entangled-ref (carry/entangle source-atom identity)]
    (is (= "#<Entangled reference: {:val 100}>" (print-str entangled-ref)))))