# Dash Docset generator for *Clojure, The Essential Reference*

Purchase a detailed API reference for Clojure’s standard library here:

https://www.manning.com/books/clojure-the-essential-reference

This project generates a [Dash docset](https://kapeli.com/dash) for quick searching:

![screenshot](screenshot.png)

## Guide

You need [Clojure CLI](https://clojure.org/guides/getting_started) and [node.js](https://nodejs.org/).

1. Download the book’s epub file to `book.epub` here.
2. Convert the epub file to a Dash docset:

```
npm ci
./build && node docset.js
```

