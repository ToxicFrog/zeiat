(defproject ca.ancilla/zeiat "0.3.0-SNAPSHOT"
  :description "A library for proxying arbitrary backend protocols to IRC"
  :url "https://github.com/toxicfrog/zeiat/"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[prismatic/schema "1.1.12"]
                 [com.taoensso/timbre "5.1.2"]]
  :global-vars {*warn-on-reflection* false
                *assert* true}
  :plugins [[io.aviso/pretty "1.1"]
            [lein-cloverage "1.0.13"]
            [lein-shell "0.5.0"]
            [lein-ancient "0.6.15"]
            [lein-changelog "0.3.2"]]
  :middleware [io.aviso.lein-pretty/inject]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.0"]
                                  [io.aviso/pretty "1.1"]
                                  ]}
             :aot {:aot :all}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"update-readme-version" ["shell" "sed" "-i" "s/\\\\[zeiat \"[0-9.]*\"\\\\]/[zeiat \"${:version}\"]/" "README.md"]}
  :release-tasks [["shell" "git" "diff" "--exit-code"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["changelog" "release"]
                  ["update-readme-version"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["vcs" "push"]])
