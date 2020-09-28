# checkrules
Check Rules

example

 docker run -v /Users/acrow/projects/genny/prj_genny/rules:/rules  gennyproject/checkrules  -a -r /rules

Here is a really cool way you can all quickly test your rules.  You can run it in 3 modes
Go into the prj root dir
(1) test individual file
 e.g. docker run -v ./rules:/rules  gennyproject/checkrules   -d /rules/rulesCurrent/shared/RULEGROUPS/IsBaseEntity/IS_HOST_CPY.drl

(2) test whole directory and test each rule one by one to help find culprit
e.g. docker run -v ./rules:/rules gennyproject/checkrules -r /rules

(3) test whole directories quickly!
e.g. docker run -v ./rules:/rules gennyproject/checkrules -a -r /rules
