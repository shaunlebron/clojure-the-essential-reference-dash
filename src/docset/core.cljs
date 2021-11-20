(ns docset.core
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [docset.io :refer [delete mkdirs copy slurp spit cd cwd]]))

(def xml2json (js/require "xml2json"))
(def parse-xml #(.toJson xml2json % #js {:object true}))

(def Database (js/require "better-sqlite3"))
(def child-process (js/require "child_process"))
(def spawn-sync (.-spawnSync child-process))
(def exec-sync (.-execSync child-process))


;; code derived from Lokeshwaran's (@dlokesh) project:
;; https://github.com/dlokesh/clojuredocs-docset

(def epub-file "book.epub")

(def docset-name "ClojureEssentialReference.docset")
(def tar-name "ClojureEssentialReference.tgz")

(def root-dir (cwd))
(def work-dir "docset")

(def docset-path (str work-dir "/" docset-name))
(def tar-path (str work-dir "/" tar-name))

(def epub-dir (str docset-path "/Contents/Resources/Documents"))
(def db-path (str docset-path "/Contents/Resources/docSet.dsidx"))

;;------------------------------------------------------------------------------
;; Parse epub toc
;;------------------------------------------------------------------------------

(defn remove-split [s] (string/replace s #"_split_00\d" ""))
(defn inc-num-in-str [s] (string/replace s #"\d+" #(inc (js/parseInt % 10))))

(defn parse-split-num [s]
  (when s
    (some-> (re-find #"_split_\d\d\d" s)
            (second)
            (js/Number.parseInt 10))))

(defn at-split [a b]
  (when-let [ai (parse-split-num a)]
    (when-let [bi (parse-split-num b)]
      (= (inc ai) bi))))

(defn add-missing-hash-after-split [a b]
  (if (and (nil? (:hash a))
           (at-split (:path a) (:path b)))
    (assoc a :hash (inc-num-in-str (:hash b)))
    a))

(defn make-toc-entry [e]
  (let [[path-old hash] (string/split (-> e :content :src) #"#")
        path (remove-split path-old)]
    {:name (-> e :navLabel :text)
     :type "Section"
     :path path
     :hash hash
     :path-old path-old}))

(defn parse-toc []
  (let [nav (-> (slurp (str epub-dir "/toc.ncx"))
                parse-xml
                (js->clj :keywordize-keys true)
                :ncx :navMap)
        entries (->> (tree-seq #(vector? (:navPoint %)) :navPoint nav)
                     next
                     vec)] ;; skip empty root
    (loop [prev nil
           [e & more] entries
           result []]
      (let [e (-> (make-toc-entry e)
                  (add-missing-hash-after-split prev))
            result (conj result e)]
        (if more
          (recur e more result)
          result)))))

;;------------------------------------------------------------------------------
;; Removing all split refs
;;------------------------------------------------------------------------------

(defn remove-split-refs! [toc]
  (cd epub-dir)
  (doseq [path (sort (distinct (map :path-old toc)))]
    (println "Removing split refs from" path)
    (spit path (remove-split (slurp path))))
  (cd root-dir))

;;------------------------------------------------------------------------------
;; Merging splits
;;------------------------------------------------------------------------------

(defn get-body [s]
  (second (re-find #"<body[^>]*>([\s\S]*)</body>" s)))

(defn append-body [html s]
  (string/replace html "</body>" (str s "</body>")))

(defn merge-splits! [toc]
  (cd epub-dir)
  (doseq [[path entries] (sort-by first (group-by :path toc))
          :let [split-paths (sort (distinct (map :path-old entries)))]
          :when (next split-paths)]
    (println (str "Merging splits into " path "..."))
    (spit path (->> (next split-paths)
                    (map (comp get-body slurp))
                    (string/join "\n")
                    (append-body (slurp (first split-paths))))))
  (cd root-dir))

;;------------------------------------------------------------------------------
;; Write TOC to Dash index
;;------------------------------------------------------------------------------

(defn path-hash [{:keys [path hash]}]
  (cond-> path
    hash (str "#" hash)))

(defn build-db! [toc]
  (let [db (new Database db-path)]
    (.run (.prepare db "DROP TABLE IF EXISTS searchIndex"))
    (.run (.prepare db "CREATE TABLE searchIndex(id INTEGER PRIMARY KEY, name TEXT, type TEXT, path TEXT)"))
    (.run (.prepare db "CREATE UNIQUE INDEX anchor ON searchIndex (name, type, path)"))
    (let [insert (.prepare db "INSERT INTO searchIndex(name, type, path) VALUES (?, ?, ?)")]
      (doseq [e toc]
        (.run insert (:name e) (:type e) (path-hash e))))
    (.close db)))

;;------------------------------------------------------------------------------
;; Add TOC markers inside each page for Dash
;; https://kapeli.com/docsets#tableofcontents
;;------------------------------------------------------------------------------

(defn toc-marker [entry]
  (let [name- (str "//apple_ref/cpp/" (:type entry) "/" (js/encodeURIComponent (:name entry)))]
    (str "<a name=\"" name- "\" class=\"dashAnchor\"></a>")))

(defn add-toc-marker [text entry]
  (if-let [hash (:hash entry)]
    (let [a (str (:hash entry) "\">")
          b (str a (toc-marker entry))]
      (string/replace text a b))
    (let [a "<h1"
          b (str (toc-marker entry) a)]
      (string/replace text a b))))

(defn add-toc-markers! [toc]
  (cd epub-dir)
  (doseq [[path entries] (group-by :path toc)]
    (let [text (slurp path)
          new-text (reduce add-toc-marker text entries)]
      (spit path new-text)))
  (cd root-dir))

;;------------------------------------------------------------------------------
;; Fix some styles
;;------------------------------------------------------------------------------

(def styles "
/* Original is too small */
.calibre { font-size: 14pt }

/* Using code block styles from:  https://livebook.manning.com */
.programlisting, .code {
  font-family: Consolas,\"Liberation Mono\",Courier,monospace;
  color: #333;
  background-color: #f8f8f8;
  border-width: 1px;
  border-style: solid;
  border-color: #dfdfdf;
  padding: 1em;
}
.code { padding: .125rem .3125rem .0625rem; }")

(defn add-styles! []
  (let [path (str epub-dir "/stylesheet.css")]
    (spit path (str (slurp path) "\n" styles))))

;;------------------------------------------------------------------------------
;; Fix some styles
;;------------------------------------------------------------------------------

(defn -main []
  (println "Creating ClojureScript docset...")

  (println "Clearing previous docset folder...")
  (delete docset-path)
  (mkdirs epub-dir)

  (println "Extracting book...")
  (spawn-sync "unzip"
    #js[epub-file "-d" epub-dir]
    #js{:stdio "inherit"})

  (println "Parsing ebook toc...")
  (let [toc (parse-toc)]

    (doseq [e toc]
      (prn e))

    (println "Removing references to split files...")
    (remove-split-refs! toc)

    (println "Merging split files...")
    (merge-splits! toc)

    (println "Adding TOC markers to files...")
    (add-toc-markers! toc)

    (println "Adding styles...")
    (add-styles!)

    (println "Copying over docset config...")
    (copy "docset/icon.png" (str docset-path "/icon.png"))
    (copy "docset/Info.plist" (str docset-path "/Contents/Info.plist"))

    (println "Creating index database...")
    (build-db! toc))

  ;; create the tar file
  (println "Creating final docset tar file...")
  (spawn-sync "tar"
    #js["--exclude='.DS_Store'" "-czf" tar-name docset-name]
    #js{:cwd work-dir :stdio "inherit"})

  (println)
  (println "Created:" docset-path)
  (println "Created:" tar-path))

(set! *main-cli-fn* -main)
(enable-console-print!)

