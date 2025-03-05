#
# Lincheck
#
# Copyright (C) 2019 - 2025 JetBrains s.r.o.
#
# This Source Code Form is subject to the terms of the
# Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
# with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
#
# This script runs all representation tests in overwrite mode.
# It runs the tests on all jdks and both in trace and non-trace modes,
# in the order jdk8 non-trace -> jdk8 trace -> jdk 11 non-trace -> jdk 11 trace, etc..
# Note that due to current issue (https://github.com/JetBrains/lincheck/issues/500) we skip jdk8 trace.
# Where necessary the output files are created or overwritten.
# One can use this script to verify trace outputs by looking at the local changes after the script ran.
# And commit the changes if accepted, to update test outputs.

# jdk 11 is SLOW! So remove it if you don't expect files to change
cd ../
jdks=("8" "11" "13" "15" "17" "19" "20" "21")
testFilter="org.jetbrains.kotlinx.lincheck_test.representation.*"
for jdk in "${jdks[@]}" 
do
  echo "[Representation Tests Overwrite] Running tests for jdk: $jdk in non-trace mode  ----------------------"
  ./gradlew jvmTest --tests "$testFilter" -PjdkToolchainVersion="$jdk" -PoverwriteRepresentationTestsOutput=true -PtestInTraceDebuggerMode=false
  
  
  #https://github.com/JetBrains/lincheck/issues/500
  if [ "$jdk" = "8" ]; then
     continue
  fi 
  
  echo "[Representation Tests Overwrite] Running tests for jdk: $jdk in trace mode  --------------------------"
  ./gradlew jvmTest --tests "$testFilter" -PjdkToolchainVersion="$jdk" -PoverwriteRepresentationTestsOutput=true -PtestInTraceDebuggerMode=true
done 