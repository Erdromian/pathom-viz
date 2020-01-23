(ns com.wsscode.pathom.viz.standalone.base)

(defonce app (atom nil))

(defn reconciler []
  (some-> app deref :reconciler))

(defn networks []
  (some-> app deref :networking))
