#!/bin/bash

if [ -z "${1}" ]; then
   version="latest"
else
   version="${1}"
fi

if [ -z "${2}" ]; then
	arch=""
else
	arch=".${2}"
fi

echo "arch=[$arch]"

docker build --no-cache -f Dockerfile${arch} -t gennyproject/checkrules:${version} .
