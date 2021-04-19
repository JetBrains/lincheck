1. In the kotlinx-lincheck directory run `./gradlew linkNative`. It generates `build/bin/native/debugShared` and `build/bin/native/releaseShared`

Run this sample:
```
g++ main.cpp ../build/bin/native/releaseShared/libnative.so && time ./a.out
```