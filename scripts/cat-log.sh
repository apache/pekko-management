#!/bin/sh
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# license agreements; and to You under the Apache License, version 2.0:
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# This file is part of the Apache Pekko project, which was derived from Akka.
#
# ---------- helper script to separate log files in build
printf "\n\n\n"
ls -lh $1
printf "\n\n"
cat $1