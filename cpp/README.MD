In the kotlinx-lincheck directory run `./gradlew linkNative`. It generates `build/bin/native/debugShared` and `build/bin/native/releaseShared`

Run this as a CMake application
1. `mkdir build`
2. `cd build`
3. `cmake ..`
4. `make`
5. `./lincheck_test`
