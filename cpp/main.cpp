#include <bits/stdc++.h>
#include "../build/bin/native/releaseShared/libnative_api.h"

extern libnative_ExportedSymbols* libnative_symbols(void);

std::atomic_int inc_count;
std::atomic_int dec_count;
int sharedState = 0;
std::atomic_int sharedState2(0);

void inc() {
    sharedState++;
    //std::cout << "operation1 changed to " << sharedState << std::endl;
    inc_count++;
    sharedState2++;
}

void dec() {
    sharedState--;
    //std::cout << "operation2 changed to " << sharedState << std::endl;
    dec_count++;
    sharedState2--;
}

int main(int argc, char** argv) {
    libnative_ExportedSymbols* lib = libnative_symbols();

    auto configuration = lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.NativeAPIStressConfiguration();

    //lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupInitialState(configuration);

    // void* cast is broken. https://stackoverflow.com/questions/36645660/why-cant-i-cast-a-function-pointer-to-void
    lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupOperationWithoutArguments(configuration, (void *)(inc), "operation1");
    lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupOperationWithoutArguments(configuration, (void *)(dec), "operation2");

    lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.runNativeTest(configuration);

    std::cout << "inc_count " << inc_count << "\n";
    std::cout << "dec_count " << dec_count << "\n";
    std::cout << "correct_diff " << inc_count - dec_count << "\n";

    std::cout << "non-atomic: " << sharedState << " vs atomic " << sharedState2;

    return 0;
}