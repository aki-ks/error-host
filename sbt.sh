#!/bin/sh
SBT_OPTS="-XX:+CMSClassUnloadingEnabled -Xms2G -Xmx4G -Xss8M -XX:MaxMetaspaceSize=4G" sbt
