#
# Lincheck
#
# Copyright (C) 2019 - 2025 JetBrains s.r.o.
#
# This Source Code Form is subject to the terms of the
# Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
# with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

# jdk 11 is SLOW! So remove it if it's not absolutely necessary
jdks=("8" "11" "13" "15" "17")
testFilter="org.jetbrains.kotlinx.lincheck_test.representation.VarHandle*"
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