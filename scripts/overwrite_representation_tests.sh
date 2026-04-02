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
# It runs the tests on all jdks, specified in array, in the order:
# jdk-default -> jdk-8 -> jdk-11, ...
# Where necessary the output files are created or overwritten.
# One can use this script to verify trace outputs by looking at the local changes after the script ran.
# And commit the changes if accepted, to update test outputs.

cd ../

# Right now only default JDK (17) output is stored, in case if another jdk
# will be required, it could be added to the list.
#
# We put JDK 17 on the first place because it is the default JDK version for tests.
# By default, when run on other JDK versions, representation tests will first
# try to compare the output with the default log file (i.e. log file for default JDK version),
# and only if they are not equal a JDK-specific expected log file will be created.
# Thus by first running tests on default JDK version we ensure that the default log file
# always created at the beginning.
jdks=("17") # ... "8" "11" "21"

testFilter="org.jetbrains.kotlinx.lincheck_test.representation.*"

for jdk in "${jdks[@]}"
do
  echo "[Representation Tests Overwrite] Running tests for jdk: $jdk  ----------------------"
  ./gradlew clean :test --tests "$testFilter" -PjdkToolchainVersion="$jdk" -PoverwriteRepresentationTestsOutput=true
done
