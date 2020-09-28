#!/bin/bash
mvn clean package -DskipTests=true
#jar uf target/checkrules-fat.jar META-INF/kie.conf
