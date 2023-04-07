# zeiat

A Clojure library for wiring up IRC to other protocols. Zeiat provides an RFC2812-compatible IRC server interface; you provide a backend implementation that talks to the actual protocol. It is oriented towards backends that require regular polling and are not always good at differentiating between read and unread messages.

⚠️ **This is not, in any sense, production-ready software.** ⚠️ It is a messy proof of concept, hacked together in a late night coding frenzy so that I could [talk to my family on googlechat](/toxicfrog/hangbrain). It has no tests, subpar documentation, and a number of exciting bugs that stem from deep-seated architectural flaws I'll need to go back and fix someday. I cannot in good conscience recommend using it, but I have published it as a learning experience. At some point -- spare time permitting, ha ha -- I want to completely redesign this, probably using core.async and ircparse.

```clj
[ca.ancilla/zeiat "0.3.0-SNAPSHOT"]
```

## Usage

Implement the `zeiat.backend/ZeiatBackend` protocol somewhere. Then call `zeiat.core/run`, passing it a function that, when called, will produce `ZeiatBackend` instances; every time a new client connects and successfully completes registration, that function will be called and the `ZeiatBackend` it returns will be coupled to that IRC client.

You can optionally pass an options map to `run`; `:poll-interval` determines how often zeiat polls the backend for new messages, and `:cache-key` determines where it stores the last-seen-messages cache, which is used to avoid re-sending already-seen messages after a restart.

See [backend.clj](src/zeiat/backend.clj) for the details of the `ZeiatBackend` protocol, and [types.clj](src/zeiat/types.clj) for the Schema type declarations.

## License

Copyright © 2021 Rebecca "ToxicFrog" Kelly

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
