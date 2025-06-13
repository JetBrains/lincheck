#!/bin/bash

#
# Lincheck
#
# Copyright (C) 2019 - 2025 JetBrains s.r.o.
#
# This Source Code Form is subject to the terms of the
# Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
# with this file, You can obtain one at http://mozilla.org/MPL/2.0/.


# This script runs all representation tests in overwrite mode.
# It runs the tests on all jdks and both in trace and non-trace modes, in the order:
# jdk-default non-trace -> jdk-default trace -> jdk-8 non-trace -> jdk 8 trace -> jdk-11 non-trace, jdk-11 trace, ...
# Where necessary the output files are created or overwritten.
# One can use this script to verify trace outputs by looking at the local changes after the script ran.
# And commit the changes if accepted, to update test outputs.

cd ../

# We put JDK 17 on the first place because it is the default JDK version for tests.
# By default, when run on other JDK versions, representation tests will first
# try to compare the output with the default log file (i.e. log file for default JDK version),
# and only if they are not equal a JDK-specific expected log file will be created.
# Thus by first running tests on default JDK version we ensure that the default log file
# always created at the beginning.
#
# JDK 11 is SLOW! So remove it if you don't expect files to change
jdks=("17" "8" "11" "21")

testFilter="org.jetbrains.kotlinx.lincheck_test.representation.*"

for jdk in "${jdks[@]}" 
do
  echo "[Representation Tests Overwrite] Running tests for jdk: $jdk in non-trace mode  ----------------------"
  ./gradlew clean test --tests "$testFilter" -PjdkToolchainVersion="$jdk" -PoverwriteRepresentationTestsOutput=true -PtestInTraceDebuggerMode=false

  #https://github.com/JetBrains/lincheck/issues/500
  if [ "$jdk" = "8" ]; then
     continue
  fi 
  
  echo "[Representation Tests Overwrite] Running tests for jdk: $jdk in trace mode  --------------------------"
  ./gradlew clean test --tests "$testFilter" -PjdkToolchainVersion="$jdk" -PoverwriteRepresentationTestsOutput=true -PtestInTraceDebuggerMode=true
done 