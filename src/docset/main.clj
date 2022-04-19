(ns docset.main
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.java.shell :refer [sh]]
    [clojure.java.jdbc :as jdbc]
    [clojure.xml :as xml]
    [me.raynes.fs :as fs])
  (:import
    [java.net URLEncoder]))

;; code derived from Lokeshwaran's (@dlokesh) project:
;; https://github.com/dlokesh/clojuredocs-docset

(def epub-file "book.epub")

(def docset-name "ClojureEssentialReference.docset")
(def tar-name "ClojureEssentialReference.tgz")

(def root-dir fs/*cwd*)
(def work-dir "docset")

(def docset-path (str work-dir "/" docset-name))
(def tar-path (str work-dir "/" tar-name))

(def epub-dir (str docset-path "/Contents/Resources/Documents"))
(def db-path (str docset-path "/Contents/Resources/docSet.dsidx"))

(def sqlite-db {:classname "org.sqlite.JDBC"
                :subprotocol "sqlite"
                :subname db-path})

(defn cwd [path]
  (str fs/*cwd* "/" path))

;;------------------------------------------------------------------------------
;; Parse epub toc
;;------------------------------------------------------------------------------

(defn xml-child [content tag]
  (->> content
       (filter #(= tag (:tag %)))
       (first)))

(defn make-toc-entry [e]
  (let [src (-> e
                :content
                (xml-child :content)
                :attrs
                :src)
        [path hash] (string/split src #"#")]
    {:name (-> e
               :content
               (xml-child :navLabel)
               :content
               (xml-child :text)
               :content
               first)
     :type "Section"
     :path path
     :hash hash}))

(defn parse-toc []
  (let [toc (xml/parse (str epub-dir "/toc.ncx"))
        nav (-> toc
                :content
                (xml-child :navMap))
        entries (->> (tree-seq :content :content nav)
                     (filter #(= :navPoint (:tag %))))]
    (map make-toc-entry entries)))

;;------------------------------------------------------------------------------
;; We don’t want chapters split up into different files,
;; So there is a multi-step process to merge them:
;;
;; 1. Fix split chapter refs in toc
;;------------------------------------------------------------------------------

(defn parse-int [s] (Integer/parseInt s))
(defn remove-split [s] (string/replace s #"_split_00\d" ""))
(defn inc-num-in-str [s] (string/replace s #"\d+" #(str (inc (parse-int %)))))

(defn parse-split-num [s]
  (some->> s
           (re-find #"_split_(\d\d\d)")
           (second)
           (parse-int)))

(defn at-split? [a b]
  (when-let [ai (parse-split-num a)]
    (when-let [bi (parse-split-num b)]
      (= ai (inc bi)))))

(defn add-missing-hash-after-split [a b]
  (if (and (nil? (:hash a))
           (at-split? (:split-path a) (:split-path b)))
    (assoc a :hash (inc-num-in-str (:hash b)))
    a))

(defn fix-toc-splits [toc]
  (let [toc (for [e toc]
              (-> e
                  (assoc :split-path (:path e))
                  (update :path remove-split)))]
    (loop [prev nil
           [e & more] toc
           result []]
      (let [e (add-missing-hash-after-split e prev)
            result (conj result e)]
        (if more
          (recur e more result)
          result)))))

;;------------------------------------------------------------------------------
;; ...
;; 2. Remove all split hrefs from pages
;;------------------------------------------------------------------------------

(defn remove-split-hrefs! [toc]
  (fs/with-cwd epub-dir
    (doseq [path (sort (distinct (map :split-path toc)))]
      (println "Removing split refs from" path)
      (spit (cwd path) (remove-split (slurp (cwd path)))))))

;;------------------------------------------------------------------------------
;; ...
;; 3. Merge splits into a single file per chapter.
;;------------------------------------------------------------------------------

(defn get-body [s]
  (second (re-find #"<body[^>]*>([\s\S]*)</body>" s)))

(defn append-body [html s]
  (string/replace html "</body>" (str s "</body>")))

(defn merge-splits! [toc]
  (fs/with-cwd epub-dir
    (doseq [[path entries] (sort-by first (group-by :path toc))
            :let [split-paths (sort (distinct (map :split-path entries)))]
            :when (next split-paths)]
      (println (str "Merging splits into " path "..."))
      (spit (cwd path) (->> (next split-paths)
                            (map (comp get-body slurp cwd))
                            (string/join "\n")
                            (append-body (slurp (cwd (first split-paths)))))))))

;;------------------------------------------------------------------------------
;; Write TOC to Dash index
;;------------------------------------------------------------------------------

(defn path-hash [{:keys [path hash]}]
  (cond-> path
    hash (str "#" hash)))

(defn search-index-attributes [e]
  {:name (:name e)
   :type (:type e)
   :path (path-hash e)})

(defn build-db! [toc]
  (jdbc/with-connection sqlite-db
    (jdbc/do-commands
      "DROP TABLE IF EXISTS searchIndex"
      "CREATE TABLE searchIndex(id INTEGER PRIMARY KEY, name TEXT, type TEXT, path TEXT)"
      "CREATE UNIQUE INDEX anchor ON searchIndex (name, type, path)"))
  (let [rows (map search-index-attributes toc)]
    (jdbc/with-connection sqlite-db
      (apply jdbc/insert-records :searchIndex rows))))

;;------------------------------------------------------------------------------
;; Add TOC preview for each chapter file:
;; https://kapeli.com/docsets#tableofcontents
;;------------------------------------------------------------------------------

(defn toc-marker [entry]
  (let [name- (str "//apple_ref/cpp/" (:type entry) "/" (URLEncoder/encode (:name entry) "UTF-8"))]
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
  (fs/with-cwd epub-dir
    (doseq [[path entries] (group-by :path toc)]
      (let [text (slurp (cwd path))
            new-text (reduce add-toc-marker text entries)]
        (spit (cwd path) new-text)))))

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
  (fs/delete-dir docset-path)
  (fs/mkdirs epub-dir)

  (println "Extracting book...")
  (sh "unzip" epub-file "-d" epub-dir)

  (let [_ (println "Parsing ebook TOC...")
        toc (parse-toc)
        _ (println "Fixing TOC chapter splits...")
        toc (fix-toc-splits toc)]

    (println "Fixing hyperlinks to chapter splits...")
    (remove-split-hrefs! toc)

    (println "Merging chapter splits...")
    (merge-splits! toc)

    (println "Adding TOC markers to files...")
    (add-toc-markers! toc)

    (println "Adding styles...")
    (add-styles!)

    (println "Copying over docset config...")
    (fs/copy "docset/icon.png" (str docset-path "/icon.png"))
    (fs/copy "docset/Info.plist" (str docset-path "/Contents/Info.plist"))

    (println "Creating index database...")
    (build-db! toc))

  ;; create the tar file
  (println "Creating final docset tar file...")
  (sh "tar" "--exclude='.DS_Store'" "-czf" tar-name docset-name :dir work-dir)

  (println)
  (println "Created:" docset-path)
  (println "Created:" tar-path)
  (System/exit 0))

