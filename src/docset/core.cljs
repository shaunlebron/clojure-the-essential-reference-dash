(ns docset.core
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [docset.io :refer [delete mkdirs copy slurp spit]]))

(def xml2json (js/require "xml2json"))
(def parse-xml #(.toJson xml2json % #js {:object true}))

(def Database (js/require "better-sqlite3"))
(def child-process (js/require "child_process"))
(def spawn-sync (.-spawnSync child-process))
(def exec-sync (.-execSync child-process))


;; code derived from Lokeshwaran's (@dlokesh) project:
;; https://github.com/dlokesh/clojuredocs-docset

(def epub-file "book.epub")
(def epub-dir "epub")

(def docset-name "ClojureEssentialReference.docset")
(def tar-name "ClojureEssentialReference.tgz")

(def work-dir "docset")

(def docset-path (str work-dir "/" docset-name))
(def tar-path (str work-dir "/" tar-name))

(def docset-docs-path (str docset-path "/Contents/Resources/Documents"))
(def db-path (str docset-path "/Contents/Resources/docSet.dsidx"))

(defn build-db! [entries]
  (let [db (new Database db-path)]
    (.run (.prepare db "DROP TABLE IF EXISTS searchIndex"))
    (.run (.prepare db "CREATE TABLE searchIndex(id INTEGER PRIMARY KEY, name TEXT, type TEXT, path TEXT)"))
    (.run (.prepare db "CREATE UNIQUE INDEX anchor ON searchIndex (name, type, path)"))
    (let [insert (.prepare db "INSERT INTO searchIndex(name, type, path) VALUES (?, ?, ?)")]
      (doseq [e entries]
        (.run insert (:name e) (:type e) (:path e))))
    (.close db)))

(defn make-toc-entry [e]
  (let [path (-> e :content :src)
        [filename anchor] (string/split path #"#")
        name- (-> e :navLabel :text)]
    {:path path
     :name name-
     :type "Section"
     :filename filename
     :anchor anchor
     }))

(defn parse-toc-entries []
  (let [nav (-> (slurp (str epub-dir "/toc.ncx"))
                (parse-xml)
                (js->clj :keywordize-keys true)
                :ncx :navMap)]
    (->> (tree-seq #(vector? (:navPoint %)) :navPoint nav)
         (drop 1) ;; skip empty root
         (map make-toc-entry))))

;; See https://kapeli.com/docsets#tableofcontents
(defn docset-toc-tag [entry]
  (str "<a name=\"//apple_ref/cpp/"
       (:type entry)
       "/"
       (js/encodeURIComponent (:name entry))
       "\" class=\"dashAnchor\"></a>"))

(defn add-docset-toc-anchor [text entry]
  (if-let [anchor (:anchor entry)]
    (let [a (str (:anchor entry) "\">")
          b (str a (docset-toc-tag entry))]
      (string/replace text a b))
    (let [a "<h1"
          b (str (docset-toc-tag entry) a)]
      (string/replace text a b))))

(defn inject-docset-toc-anchors! [toc]
  (doseq [[filename entries] (group-by :filename toc)]
    (let [fname (str docset-docs-path "/" filename)
          text (slurp fname)
          new-text (reduce add-docset-toc-anchor text entries)]
      (spit fname new-text))))

(defn -main []
  (println "Creating ClojureScript docset...")

  (println "Clearing previous docset folder...")
  (delete docset-path)
  (mkdirs docset-docs-path)

  (println "Extracting book...")
  (delete epub-dir)
  (spawn-sync "unzip"
    #js[epub-file "-d" epub-dir]
    #js{:stdio "inherit"})

  (println "Copying over docset pages...")
  (copy "docset/icon.png" (str docset-path "/icon.png"))
  (copy "docset/Info.plist" (str docset-path "/Contents/Info.plist"))
  (copy epub-dir docset-docs-path) ;; epub/* files are moved to the docs/*

  (let [entries (parse-toc-entries)]
    (println "Creating index database...")
    (build-db! entries)

    (println "Writing toc anchors...")
    (inject-docset-toc-anchors! entries))

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

