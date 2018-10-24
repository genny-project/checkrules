#!/bin/bash
if [ -z "${1}" ]; then
   version="latest"
else
   version="${1}"
fi

docker push gennyproject/checkrules:"${version}"
docker tag gennyproject/checkrules:"${version}" gennyproject/checkrules:latest
docker push gennyproject/checkrules:latest
