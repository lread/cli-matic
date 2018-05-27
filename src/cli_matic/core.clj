(ns cli-matic.core
  (:require [cli-matic.specs :as S]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.spec.alpha :as s]
            [orchestra.spec.test :as st]
            [cli-matic.presets :as PRESETS]))

;; ================ ATTENTION ====================
;; Cli-matic has one main entry-point: run!
;; Actually, most of the logic will be run in run*
;; to make testing easier.
;;
;; -----------------------------------------------


(defn assoc-new-multivalue
  "Associates a new multiple value to the
  current parameter map.
  If the current value is not a vector, creates
  a new vector with the new value."
  [parameter-map option v]
  (let [curr-val (get parameter-map option [])
        new-val (if (vector? curr-val)
                  (conj curr-val v)
                  [v])]
    (assoc parameter-map option new-val)))

;; Rewrite options from our format
;; {:opt "x" :as "Port number" :type :int}
;; to tools.cli:
;; ["-x" nil "Port number"
;;  :parse-fn #(Integer/parseInt %)]
;; as specified in
;;  https://github.com/clojure/tools.cli/blob/master/src/main/clojure/clojure/tools/cli.clj#L488
;;

(defn mk-cli-option
  "Builds a tools.cli option out of our own format.

  "
  [{:keys [option short as type default multiple] :as cm-option}]

  (let [preset (get PRESETS/known-presets type :unknown)
        placeholder (str (:placeholder preset)
                         (if (= :present default) "*" ""))
        positional-opts [(if (string? short)
                           (str "-" short)
                           nil)
                         (str "--" option " " placeholder)
                         as]
        ;; step 1 - remove :placeholder
        opts-1 (dissoc preset :placeholder)

        ;; step 2 - add default if present and is not ":present"
        opts-2 (if (and (some? default) (not= :present default))
                 (assoc opts-1 :default default)
                 opts-1)

        ;; step 3 - if multivalue, add correct assoc-fns
        opts-3 (if multiple
                 (assoc opts-2 :assoc-fn assoc-new-multivalue)
                 opts-2)]
    (apply
     conj positional-opts
     (flatten (seq opts-3)))))

(s/fdef mk-cli-option
        :args (s/cat :opts ::S/climatic-option)
        :ret some?)

(defn get-subcommand
  "Given args and the canonical name of a subcommand,
  returns the map describing it.
  "
  [climatic-args subcmd]
  (let [subcommands (:commands climatic-args)]
    (first (filter #(= (:command %) subcmd) subcommands))))

(s/fdef get-subcommand
        :args (s/cat :args ::S/climatic-cfg :subcmd string?)
        :ret ::S/a-command)

(defn all-subcommands-aliases
  "Maps all subcommands and subcommand aliases
  to their canonical name.
  E.g. {'add': 'add', 'a': 'add'}.

  We basically add them all, then remove
  nil keys.

  "

  [climatic-args]
  (let [subcommands (:commands climatic-args)]

    (dissoc
     (merge
        ;; a map of 'cmd' -> 'cmd'
      (into {}
            (map
             (fn [{:keys [command short]}]
               [command command])
             subcommands))

      (into {}
            (map
             (fn [{:keys [command short]}]
               [short command])
             subcommands)))
     nil)))

(s/fdef all-subcommands-aliases
        :args (s/cat :args ::S/climatic-cfg)
        :ret (s/map-of string? string?))

(defn all-subcommands
  "Returns all subcommands, as strings.
   We get all versions of all subcommands.
  "
  [climatic-args]
  (set (keys (all-subcommands-aliases climatic-args))))

(s/fdef all-subcommands
        :args (s/cat :args ::S/climatic-cfg)
        :ret set?)

(defn canonicalize-subcommand
  "Returns the 'canonical' name of a subcommand,
  i.e. the one that appears in :command, even
  if we pass an alias or short version."
  [commands subcmd]
  (get (all-subcommands-aliases commands) subcmd))

(s/fdef canonicalize-subcommand
        :args (s/cat :args ::S/climatic-cfg :sub string?)
        :ret string?)

(defn get-options-for
  "Gets specific :options for a subcommand or,
  if nil, for global."
  [climatic-args subcmd]
  (if (nil? subcmd)
    (:global-opts climatic-args)
    (:opts (get-subcommand climatic-args subcmd))))

;; Out of a cli-matic arg list,
;; generates a set of commands for tools.cli
(defn rewrite-opts
  "
  Out of a cli-matic arg list, generates a set of
  options for tools.cli.
  It also adds in the -? and --help options
  to trigger display of helpness.
  "
  [climatic-args subcmd]
  (conj
   (mapv mk-cli-option (get-options-for climatic-args subcmd))
   ["-?" "--help" "" :id :_help_trigger]))

(s/fdef rewrite-opts
        :args (s/cat :args some?
                     :mode (s/or :common nil?
                                 :a-subcommand string?))
        :ret some?)

;; ------------------------------------------------
;; Stuff to generate help pages
;; ------------------------------------------------

(defn asString
  "Turns a collection of strings into one string,
  or the string itself."
  [s]
  (if (string? s)
    s
    (clojure.string/join "\n" s)))

(defn indent-string
  "Indents a single string."
  [s]
  (str " " s))

(defn indent
  "Indents a single string, or each string
  in a collection of strings."
  [s]
  (if (string? s)
    (indent-string s)
    (map indent-string (flatten s))))

(defn generate-section
  "Generates a section (as a collection of strings,
  possibly nested, but we'll flatten it out).
  If a section has no content, we return [].
  "
  [title lines]
  (if (empty? lines)
    []

    [(str title ":")
     (indent lines)
     ""]))

(defn generate-sections
  "Generates all sections.
  All those positional parameters are not that nice.
  "
  [name version usage commands opts-title opts]

  (vec
   (flatten
    [(generate-section "NAME" name)
     (generate-section "USAGE" usage)
     (generate-section "VERSION" version)
     (generate-section "COMMANDS" commands)
     (generate-section opts-title opts)])))

(defn get-options-summary
  "To get the sumamry of options, we pass options to
  tools.cli parse-opts and an empty set of arguments.
  Parsing will fail but we get the :summary.
  We then split it into a collection of lines."
  [cfg subcmd]
  (let [cli-cfg (rewrite-opts cfg subcmd)
        options-str (:summary
                     (parse-opts [] cli-cfg))]
    (clojure.string/split-lines options-str)))

(defn get-first-rest-description-rows
  "get title and description of description rows"
  [row-or-rows]
  (cond
    (string? row-or-rows)
    [row-or-rows []]

    (zero? (count row-or-rows))
    ["?" []]

    :else
    [(first row-or-rows) (rest row-or-rows)]))

(defn pad
  "Pads s[, s1] to so many characters"
  [s s1 len]
  (subs (str s
             (when s1
               (str ", " s1))
             "                   ")
        0 len))

(defn generate-a-command
  "Maybe we should use a way to format commands

   E.g.
   (pp/cl-format true \"~{ ~vA  ~vA  ~vA ~}\" v)"

  [{:keys [command short description]}]

  (let [[des0 _] (get-first-rest-description-rows description)]
    (str "  "
         (pad command short 20)
         " "
         des0)))

(defn generate-global-command-list
  "Creates a list of commands and descriptions.
   Commands are of kind ::S/commands
  "
  [commands]
  (map generate-a-command commands))

(s/fdef
 generate-global-command-list
 :args (s/cat :commands ::S/commands)
 :ret  (s/coll-of string?))

(defn generate-global-help
  "This is where we generate global help, so
  global attributes and subcommands."

  [cfg]

  (let [name (get-in cfg [:app :command])
        version (get-in cfg [:app :version])
        descr (get-in cfg [:app :description])
        [desc0 descr-extra] (get-first-rest-description-rows descr)]

    (generate-sections
     [(str name " - " desc0) descr-extra]
     version
     (str name " [global-options] command [command options] [arguments...]")
     (generate-global-command-list (:commands cfg))
     "GLOBAL OPTIONS"
     (get-options-summary cfg nil))))

(s/fdef
 generate-global-help
 :args (s/cat :cfg ::S/climatic-cfg)
 :ret (s/coll-of string?))

(defn generate-subcmd-help
  "This is where we generate help for a specific subcommand."
  [cfg cmd]

  (let [glname (get-in cfg [:app :command])
        cmd-cfg (get-subcommand cfg cmd)
        name (:command cmd-cfg)
        shortname (:short cmd-cfg)
        name-short (if shortname
                     (str "[" name "|" shortname "]")
                     name)
        descr (:description cmd-cfg)
        [desc0 descr-extra] (get-first-rest-description-rows descr)]

    (generate-sections
     [(str glname " " name " - " desc0) descr-extra]
     nil
     (str glname " " name-short " [command options] [arguments...]")
     nil
     "OPTIONS"
     (get-options-summary cfg cmd))))

(s/fdef
 generate-subcmd-help
 :args (s/cat :cfg ::S/climatic-cfg :cmd ::S/command)
 :ret (s/coll-of string?))

;; -----------------------------------------------------
;; Here we parse our command line.
;; -----------------------------------------------------

(defn mkError
  "Builds an error condition."
  [config subcommand error text]
  {:subcommand     subcommand
   :subcommand-def (if (or (= error :ERR-UNKNOWN-SUBCMD)
                           (= error :ERR-NO-SUBCMD)
                           (= error :ERR-PARMS-GLOBAL)
                           (= error :HELP-GLOBAL))
                     nil
                     (get-subcommand config subcommand))
   :commandline    {}
   :parse-errors   error
   :error-text     (asString text)})

;; TODO s/fdef

(defn errors-for-missing-mandatory-args
  "Gets us a sequence of errors if mandatory options are missing"
  [climatic-options parsed-opts]
  (let [mandatory-options (filter
                           #(= :present (:default %))
                           climatic-options)
        curr-options (:options parsed-opts)]

    (reduce
     (fn [a v]
       (let [optname  (:option v)
             val (get curr-options (keyword optname) :MISSING)]

         (if (= :MISSING val)
           (conj a (str "Missing option: " optname))
           a)))
     []
     mandatory-options)))

(s/fdef errors-for-missing-mandatory-args
        :args (s/cat :options ::S/opts
                     :parsed-opts map?)
        :ret (s/coll-of string?))

(defn parse-cmds
  "This is where magic happens.
  We first parse global options, then stop,
  get the subcommand, parse specific options for the subcommand
  and if all went well we prepare run it.

  This fuction returns a structure ::S/lineParseResult
  that containe information about what went wrong or the command
  to run.
  "
  [cmdline config]

  (let [cli-gl-options (rewrite-opts config nil)
        ;_ (prn "Cmdline" cmdline)
        parsed-gl-opts (parse-opts cmdline cli-gl-options :in-order true)
        missing-gl-opts (errors-for-missing-mandatory-args
                         (get-options-for config nil)
                         parsed-gl-opts)
        ;_ (prn "Common cmdline" parsed-common-cmdline)
        {gl-errs :errors gl-opts :options gl-args :arguments} parsed-gl-opts]

    (cond
      ; any parse errors?
      (some? gl-errs)
      (mkError config nil :ERR-PARMS-GLOBAL gl-errs)

      ; did we ask for help?
      (some? (:_help_trigger gl-opts))
      (mkError config nil :HELP-GLOBAL nil)

      :else
      (let [subcommand (first gl-args)
            subcommand-parms (vec (rest gl-args))]

        (cond
          (nil? subcommand)
          (mkError config nil :ERR-NO-SUBCMD "")

          (nil? ((all-subcommands config) subcommand))
          (mkError config subcommand :ERR-UNKNOWN-SUBCMD "")

          :else
          (let [canonical-subcommand (canonicalize-subcommand config subcommand)
                cli-cmd-options (rewrite-opts config canonical-subcommand)
                ;_ (prn "O" cli-cmd-options)
                parsed-cmd-opts (parse-opts subcommand-parms cli-cmd-options)
                missing-cmd-opts (errors-for-missing-mandatory-args
                                  (get-options-for config canonical-subcommand)
                                  parsed-cmd-opts)
                ;_ (prn "Subcmd cmdline" parsed-cmd-opts)
                ;_ (prn "G" missing-gl-opts)
                ;_ (prn "C" missing-cmd-opts)
                {cmd-errs :errors cmd-opts :options cmd-args :arguments} parsed-cmd-opts]

            (cond
              ; asking for help?
              (some? (:_help_trigger cmd-opts))
              (mkError config canonical-subcommand :HELP-SUBCMD nil)

              ; any missing required global parm?
              (pos? (count missing-gl-opts))
              (mkError config nil :ERR-PARMS-GLOBAL missing-gl-opts)

              ; missing required parms?
              (pos? (count missing-cmd-opts))
              (mkError config canonical-subcommand :ERR-PARMS-SUBCMD missing-cmd-opts)

              ; no errors?
              (nil? cmd-errs)
              {:subcommand     canonical-subcommand
               :subcommand-def (get-subcommand config canonical-subcommand)
               :commandline     (into
                                 (into gl-opts cmd-opts)
                                 {:_arguments cmd-args})
               :parse-errors    :NONE
               :error-text     ""}; having errors....
              :else
              (mkError config canonical-subcommand :ERR-PARMS-SUBCMD cmd-errs))))))))

(s/fdef
 parse-cmds
 :args (s/cat :args (s/coll-of string?)
              :opts ::S/climatic-cfg)
 :ret ::S/lineParseResult)



(defn assert-unique-values
  "Check that all values are unique.
  name is the area of the configuration
  vec-opts are the options to check
  option is the keyword to check.
  "
  [name vec-opts option]

  (let [optName (if (nil? name) "global" name)
        allOptions (filter some? (map option vec-opts))
        dupes (filterv (fn [[k v]]  (> v 1)) (frequencies allOptions))]
    (cond
      (not (empty? dupes))
      (throw (IllegalAccessException.
               (str "In option area: " optName " for options of type " option " some option names are not unique: " dupes ))))))

(s/fdef
  assert-unique-values
  :args (s/cat :name (s/or :some-subcmd ::S/existing-string
                           :global  nil?)
               :vec-opts any? ;::S/commands
               :option keyword?))



;;
;; Asserts sanity of initial configuration.
;; If this goes wrong, throws an error.
;;
(defn assert-cfg-sanity
  "Checks configuration and throws if anything wrong.

  1. are :option values unique?
  2. are :short values unique?
  "
  [currentCfg]

  (let [all-subcommands (into [nil]
                              (all-subcommands currentCfg))]

    (mapv #(assert-unique-values %
                                 (get-options-for currentCfg %)
                                 :option)
          all-subcommands)

    (mapv #(assert-unique-values %
                                 (get-options-for currentCfg %)
                                 :short)
          all-subcommands)

    nil

    ))


(s/fdef assert-cfg-sanity
        :args (s/cat :opts ::S/climatic-cfg))


;
; builds a return value
;

(defn ->RV
  "This is a Return Value, i.e. what happens after the
  parsing is done and possibly the subcommand was invoked."
  [return-code type stdout subcmd stderr]
  (let [fnStrVec (fn [s]
                   (cond
                     (nil? s) []
                     (string? s) [s]
                     :else  s))]

    {:retval return-code
     :status type
     :help   stdout
     :subcmd subcmd
     :stderr (fnStrVec stderr)}))

(s/fdef
 ->RV
 :args (s/cat :rv int? :status some? :help any? :subcmd any? :stderr any?)
 :rets ::S/RV)

;
;
;
;

(defn invoke-subcmd
  "Invokes a subcommand, and produces a Return Value.

   The subcommand may:
    - return an integer (to specify exit code)
    - return nil
    - throw a Throwable object

  "
  [subcommand-def options]

  (try
    (let [rv ((:runs subcommand-def)  options)]
      (cond
        (nil? rv)    (->RV 0 :OK nil nil nil)
        (int? rv)   (if (zero? rv)
                      (->RV 0 :OK nil nil nil)
                      (->RV rv :ERR nil nil nil))

        :else        (->RV 0 :OK nil nil nil)))

    (catch Throwable t
      (->RV -1 :EXCEPTION nil nil
            (str "JVM Exception: "
                 (with-out-str (println t)))))))

;; Executes our code.
;; It will try and parse the arguments via clojure.tools.cli
;; and detect our subcommand.

;; If no subcommand was found, it will print the error reminder.
;; On exceptions, it will raise an exception message.
(defn run-cmd*
  [setup args]
  (let [parsed-opts (parse-cmds args setup)]
    ;; maybe there was an error parsing
    (condp = (:parse-errors parsed-opts)
      :ERR-CFG (->RV -1 :ERR-CFG nil nil  "Error in cli-matic configuration")
      :ERR-NO-SUBCMD (->RV -1 :ERR-NO-SUBCMD :HELP-GLOBAL nil "No sub-command specified")
      :ERR-UNKNOWN-SUBCMD (->RV -1 :ERR-UNKNOWN-SUBCMD :HELP-GLOBAL nil "Unknown sub-command")
      :HELP-GLOBAL (->RV 0 :OK :HELP-GLOBAL nil nil)
      :ERR-PARMS-GLOBAL (->RV -1 :ERR-PARMS-GLOBAL :HELP-GLOBAL nil
                              (str "Global option error: " (:error-text parsed-opts)))
      :HELP-SUBCMD (->RV 0 :OK :HELP-SUBCMD (:subcommand parsed-opts) nil)
      :ERR-PARMS-SUBCMD (->RV -1 :ERR-PARMS-SUBCMD :HELP-SUBCMD (:subcommand parsed-opts)
                              (str "Option error: " (:error-text parsed-opts)))
      :NONE (invoke-subcmd (:subcommand-def parsed-opts) (:commandline parsed-opts)))))

(defn run-cmd
  "This is the actual function that is executed.
  It wraps run-cmd* and then does the printing
  of any errors, of help pages and  System.exit.

  "
  [args setup]
  (let [{:keys [help stderr subcmd retval]}
        (run-cmd* setup (if (nil? args) [] args))]
    (if (not (empty? stderr))
      (println
       (asString
        (flatten
         ["** ERROR: **" stderr "" ""]))))
    (cond
      (= :HELP-GLOBAL help)
      (println (asString (generate-global-help setup)))
      (= :HELP-SUBCMD help)
      (println (asString (generate-subcmd-help setup subcmd))))
    (System/exit retval)))

(st/instrument)
