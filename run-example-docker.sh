#/bin/bash

docker run -v $PWD:/rules  -it --rm gennyproject/checkrules -r /rules 
