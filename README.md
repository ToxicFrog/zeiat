# zeiat

A Clojure library for wiring up IRC to other protocols. Zeiat provides an RFC2812-compatible IRC server interface; you provide a backend implementation that talks to the actual protocol.

```clj
[zeiat "0.0.0"]
```

## Usage

Create an implementation of the `zeiat.backend/ZeiatBackend` protocol and pass it to `zeiat.core/run` (if you want Zeiat to manage the listen socket for you) or `zeiat.core/create` (if you already have a socket connected to an IRC client). See the docstrings for those namespaces for more details.

TODO: write better documentation.

`run` will run until the socket is closed, then return a list of still-connected clients.

For every connection opened, it will call the `create` function passed to `run`, passing it one argument, `reply-fn`. It should return an instance of `ZeiatBackend`.

## License

Copyright © 2021 Rebecca "ToxicFrog" Kelly

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
