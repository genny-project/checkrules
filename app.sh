#!/bin/bash
java -Dkie.maven.offline.force=true  -Djava.security.egd=file:/dev/./urandom -jar  /app.jar  "$@"
