# *Clojure, The Essential Reference* (for Dash)

If you want a detailed API reference for Clojure’s standard library…

Buy the excellent book _[Clojure, The Essential Reference][book]_ by Renzo Borgatti.

To quickly navigate to any API symbol in this book, this repo makes its EPUB file browsable as a [Dash docset][dash]:

![screenshot](screenshot.png)

[dash]:https://kapeli.com/dash
[book]:https://www.manning.com/books/clojure-the-essential-reference

## Docset features

* Every heading is indexed as a Docset section.
* Every chapter has its own Docset page.
* Table of Contents sidebar for each chapter.

## Create the Docset

1. Buy the book, and download the epub file to `book.epub`.
1. Use [Clojure CLI](https://clojure.org/guides/getting_started) to build the node.js file:

    ```
    ./build
    ```

1. Run the following to generate the docset:

    ```
    npm ci
    node docset.js
    ```

1. Import `docset/ClojureEssentialReference.docset` into Dash under Preferences > Docsets.

