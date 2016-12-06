# SlipStream run proxy and library API for it.

## Building

With Maiven
```
mvn clean install
```

With boot

```
boot mvn-build
boot mvn-build-api-jar
```

Resulting artifacts become available in local Maven repo under

```
~/.m2/repository/com/sixsq/slipstream/SlipStreamRunProxyServer-jar/
~/.m2/repository/com/sixsq/slipstream/SlipStreamRunProxyApi-jar/
```

`SlipStreamRunProxyServer-jar` is an uberjar and contains all the
dependencies to be able to start independently.

## Running

Starting service with uberjar and binding to 8008 on localhost

```
$ export SERVER_PORT=8008
$ java -cp SlipStreamRunProxyServer-jar-3.16-SNAPSHOT.jar
```

## Copyright

Copyright © 2016 SixSq Sarl. All rights reserved.
