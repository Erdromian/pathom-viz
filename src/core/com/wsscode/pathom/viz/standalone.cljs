(ns com.wsscode.pathom.viz.standalone
  (:require [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.viz.standalone.base :as base]
            [com.wsscode.pathom.viz.index-explorer :as iex]
            [com.wsscode.pathom.diplomat.http :as p.http]
            [com.wsscode.pathom.diplomat.http.fetch :as p.http.fetch]
            [com.wsscode.pathom.viz.query-editor :as pv.query-editor]
            [com.wsscode.pathom.viz.ui.kit :as ui]
            [com.wsscode.pathom.fulcro.network :as p.network]
            [cognitect.transit :as transit]
            [com.wsscode.common.async-cljs :refer [<? go-catch]]
            [fulcro-css.css-injection :as css-injection]
            [fulcro.client :as fc]
            [fulcro.client.network :as network]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.localized-dom :as dom]
            [fulcro.client.primitives :as fp]
            [clojure.string :as str]))

(fp/defsc Root [this {:keys [ui/url ui/query-editor >/index-explorer] :as props}]
          {:query             [:ui/url
                               {:ui/query-editor (fp/get-query pv.query-editor/QueryEditor)}
                               {:>/index-explorer (fp/get-query iex/IndexExplorer)}]
           :initial-state     (fn [{:keys [url]}]
                                {:ui/url           url
                                 :ui/query-editor  (fp/get-initial-state pv.query-editor/QueryEditor {})
                                 :>/index-explorer (fp/get-initial-state iex/IndexExplorer {})})
           :ident             (fn [] [:>/pathom-viz :singleton])
           :css               [[:.main-content
                                {:display        :flex
                                 :flex-direction "column"
                                 :max-width      "100%"
                                 :overflow       "hidden"
                                 :flex           1}]
                               [:.upper
                                {:resize        "vertical"
                                 :height        "70vh"
                                 :overflow-y    "scroll"
                                 :margin-bottom "5px"}]
                               [:.lower
                                {:resize "vertical"
                                 :flex   1
                                 :height "100%"}]]
           :componentDidMount (fn [] (df/load
                                               this
                                               (->> this fp/props :>/index-explorer (fp/get-ident iex/IndexExplorer))
                                               iex/IndexExplorer))
           :css-include       [pv.query-editor/QueryEditor iex/IndexExplorer]}
          (cond
            (not url)
            (dom/div "Please enter a valid URL in the prompt/url query param")

            (not (-> index-explorer ::iex/idx))
            (dom/div (str "Waiting for index from URL: " url))

            :else
            (dom/main
              :.main-content
              (css-injection/style-element {:component this})
              (dom/div
                :.upper
                (iex/index-explorer index-explorer))
              (dom/div
                :.lower
                (-> query-editor
                    (merge {::pc/indexes (-> index-explorer ::iex/idx)})
                    (fp/computed {:default-trace-size 100})
                    (pv.query-editor/query-editor))))))

(def transit-request-middleware
  (network/wrap-fulcro-request #(assoc-in % [:headers "accept"] "application/transit+json")))

(defn ->fulcro-remote [{:keys [url]}]
  (network/fulcro-http-remote {:url                url
                               :request-middleware transit-request-middleware}))

(defn mount []
  (reset! base/app (fc/mount @base/app Root "app")))


(defn transit-read [x]
  (-> (transit/read (transit/reader :json) x)))

(defn transit-write [x]
  (-> (transit/write (transit/writer :json) x)))

(defn http-request-parser [url]
  (fn [_env tx]
    (go-catch
      (let [{::p.http/keys [body]}
            (<? (p.http/request {::p.http/driver       p.http.fetch/request-async
                                 ::p.http/url          url
                                 ::p.http/content-type ::p.http/transit+json
                                 ::p.http/method       ::p.http/post
                                 ::p.http/headers      {"accept" "application/transit+json"}
                                 ::p.http/form-params  (transit-write tx)}))]
        (transit-read body)))))

(defn ^:export init []
  (let [remote-url  (or (.get (js/URLSearchParams. js/window.location.search) "url")
                        (->> ["Please enter a Pathom endpoint like:"
                              "http://localhost/pathom"
                              "You can pass this in through the 'url' query parameter."]
                             (str/join \newline)
                             (js/prompt)))
        remote-data {:remote {:url remote-url}}
        remotes     (into {pv.query-editor/remote-key (-> remote-url
                                                          http-request-parser
                                                          pv.query-editor/client-card-parser
                                                          p.network/pathom-remote)}
                          (map (fn [[k v]] [k (->fulcro-remote v)])
                               remote-data))]
    (reset! base/app (fc/make-fulcro-client
                       {:reconciler-options {:render-mode :keyframe}
                        :initial-state      (fp/get-initial-state Root {:url remote-url})
                        :networking         remotes}))
    (mount)))
