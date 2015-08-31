;*******************************************************************************
;* Copyright (c) 2010 Laurent PETIT.
;* All rights reserved. This program and the accompanying materials
;* are made available under the terms of the Eclipse Public License v1.0
;* which accompanies this distribution, and is available at
;* http://www.eclipse.org/legal/epl-v10.html
;*
;* Contributors: 
;*    Laurent PETIT - initial API and implementation
;*    Andrea Richiardi - SourceViewer* wrapper
;*******************************************************************************/
(ns
  ^{:doc 
    "Helper functions for the Clojure Editor.

     Related to parse tree and text buffer:
       - the state holds a ref, which is a map containing keys
         :text                    the full text corresponding to the incremental text buffer
         :incremental-text-buffer the incrementally editable text buffer
         :previous-parse-tree     the previous parse-tree, or nil if no previous parse-tree
         :parse-tree              the parse-tree related to the :text-buffer
         :build-id                the build id, identifying a \"version\" of the parse-tree
                                    (used for determining deltas between 2 updates)
   "}
  ccw.editors.clojure.editor-support 
  (:require [paredit.parser :as p]
            [paredit.loc-utils :as lu]
            [paredit.static-analysis :as static-analysis]
            [ccw.jdt :as jdt]
            [ccw.events :as evt]
            [ccw.eclipse :as e]
            [ccw.core.trace :as t]
            [ccw.swt :as swt])
  (:import ccw.editors.clojure.IClojureEditor
           org.eclipse.ui.texteditor.SourceViewerDecorationSupport))

#_(set! *warn-on-reflection* true)

(defn- safe-edit-buffer [buffer offset len text final-text]
  (try
    (p/edit-buffer buffer offset len text)
    (catch Exception e
      (t/trace :editor
        (str "--------------------------------------------------------------------------------" \newline
             "Error while editing parsley buffer. offset:" offset ", len:" len ", text:'" text "'" \newline
             "buffer text:'" (-> buffer (p/buffer-parse-tree 0) lu/node-text) "'"))
      (p/edit-buffer nil 0 0 final-text))))

(defn updateTextBuffer [r final-text offset len text]
  (let [r (if (nil? r) (ref nil) r), text (or text ""), build-id (if-let [old (:build-id @r)] (inc old) 0)] 
    (dosync
      (when-not (= final-text (:text @r))
        (let [buffer (safe-edit-buffer (:incremental-text-buffer @r) offset len text final-text)
              parse-tree (p/buffer-parse-tree buffer build-id)]
          (if-not true #_(= final-text (lu/node-text parse-tree)) ;;;;;;;;; TODO remove this potentially huge perf sucker!
            (do
              (t/trace :editor
                (str
                  "Doh! the incremental update did not work well. "
                  \newline
                  "offset:" offset ", " "len:" len ", " (str "text:'" text "'")
                  \newline
                  "final-text passed as argument:'" final-text "'" ", but text recomputed from parse-tree: '" (lu/node-text parse-tree) "'"
                  \newline
                  "What happened ? Will throw away the current buffer and start with a fresh one..."))
              (let [buffer (p/edit-buffer nil 0 -1 final-text)
                    parse-tree (p/buffer-parse-tree buffer build-id)]
                (ref-set r {:text final-text, :incremental-text-buffer buffer, :previous-parse-tree (:parse-tree @r), :parse-tree parse-tree, :build-id build-id})))
            (ref-set r {:text final-text, :incremental-text-buffer buffer, :previous-parse-tree (:parse-tree @r), :parse-tree parse-tree :build-id build-id}))
          )))
    r))

(defn init-text-buffer
  "Initialize that input parse state with the input initial-text. Return
  the new parse state."
  [parse-state initial-text]
  {:pre [(nil? parse-state)]
   :post [(= (:build-id (deref %)) 0)]}
  (let [parse-state (ref nil)
        build-id 0]
    (dosync
     ;; AR - The buffer needs to be recalculated if the transaction is retried
     (let [buffer (p/edit-buffer nil 0 -1 initial-text)
           parse-tree (p/buffer-parse-tree buffer build-id)]
       (ref-set parse-state {:text initial-text
                             :incremental-text-buffer buffer
                             :previous-parse-tree nil
                             :parse-tree parse-tree
                             :build-id build-id})))
    (t/trace :editor (str "Parse state initialized!"
                          \newline
                          "text:" :text
                          \newline
                          "parse tree elements:" (count (:parse-tree @parse-state))))
    parse-state))

(defn startWatchParseRef [r editor]
  (add-watch r :track-state (fn [_ _ _ new-state] 
                              (.setStructuralEditionPossible editor 
                                (let [possible? (not (nil? (:parse-tree new-state)))
                                      possible? (or possible? (.isEmpty ^String (:text new-state)))]
                                  possible?)))))

(defn getParseTree [parse-state] (:parse-tree parse-state))

(defn brokenParseTree? [parse-state]
  (if-let [parse-tree (getParseTree parse-state)]
    (boolean (:broken? parse-tree))
    true))
                        
(defn getParseState 
  "text is passed to check if the contents of r is still up to date or not.
   If not, text will also be used to recompute r on-the-fly."
  [text r]
  (let [rv @r] 
    (if (= text (:text rv))
      {:parse-tree (:parse-tree rv), :buffer (:incremental-text-buffer rv)}
      (do
        (t/trace :editor (str "cached parse-tree miss: expected text='" (:text rv) "'" ", text received: '" text "'"))
        (updateTextBuffer r text 0 -1 text)
        (recur text r)))))

(defn top-level-code-form 
  "Return the top level form which corresponds to code for the current offset" 
  [parse-state offset]
  (when-let [parse-tree (getParseTree parse-state)]
    (let [root-loc (lu/parsed-root-loc parse-tree)
          top-level-loc (static-analysis/top-level-code-form root-loc offset)]
      (lu/loc-text top-level-loc))))

;; Now, I don't like the fact that getting the current and the previous parse tree may lead to incorrect code
;; since they both are dereferencing a ref instead of decomposing a consistent snapshot of a ref
(defn getPreviousParseTree 
  [r]
  (:previous-parse-tree @r))

(defn disposeSourceViewerDecorationSupport [^SourceViewerDecorationSupport s]
  (when s
    (doto s .uninstall .dispose)
    nil))

(defn configureSourceViewerDecorationSupport [^SourceViewerDecorationSupport support ^IClojureEditor viewer]
		;; TODO more to pick in configureSourceViewerDecorationSupport of AbstractDecoratedTextEditor, if you want ...
  (doto support
    (.setCharacterPairMatcher (.getPairsMatcher viewer))
    (.setMatchingCharacterPainterPreferenceKeys
      (jdt/preference-constants ::jdt/editor-matching-brackets)
      (jdt/preference-constants ::jdt/editor-matching-brackets-color))))

(defn editor-saved [editor]
  (evt/post-event :ccw.editor.saved
    {:namespace     (.findDeclaringNamespace editor)
     :absolute-path (some-> editor .getEditorInput e/resource .getLocation .toOSString)
     :repl          (-> editor .getCorrespondingREPL)}))

(defn source-viewer
  "Return the ClojureSourceViewer of the input IClojureEditor, might
  return nil. The 0-arity tries to get it from the Active editor."
  ([] (source-viewer (e/workbench-active-editor)))
  ([^IClojureEditor editor]
   {:pre [(not (nil? editor))]}
   (.sourceViewer editor)))

(defn set-status-line-error-msg-async
  "The function wraps IClojureAwarePart.setStatusLineErrorMessage in an async ui-only call.
  Remember that passing nil as message resets the status line."
  [^IClojureEditor part message]
  (swt/doasync (.setStatusLineErrorMessage part message)))

(defn open-clojure-editors
  "Return all open clojure editors of the Workbench, pred is an optional
  predicate for filtering them."
  ([] (open-clojure-editors identity))
  ([pred] (map (comp #(e/adapter % IClojureEditor) #(.getEditor % true)) (e/open-editor-refs identity))))
