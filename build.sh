#!/bin/bash
mvn clean package
jar uf target/checkrules-fat.jar META-INF/kie.conf
