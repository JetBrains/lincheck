#include <bits/stdc++.h>
#include "lincheck.h"

class Instance {
public:
    int sharedState = 0;
    std::atomic_int sharedAtomicState = 0;

    int inc() {
        auto ans = ++sharedState;
        //std::cout << "inc " << ans << std::endl;
        return ans;
    }

    int dec() {
        auto ans = --sharedState;
        //std::cout << "dec " << ans << std::endl;
        return ans;
    }

    int add(unsigned long value) {
        auto ans = sharedState += value;
        return ans;
    }

    int atomic_inc() {
        return ++sharedAtomicState;
    }

    int atomic_dec() {
        return --sharedAtomicState;
    }

    int atomic_add(unsigned long value) {
        auto ans = sharedAtomicState += value;
        return ans;
    }

    Instance() {
        //std::cout << "SharedInstance has created" << std::endl;
    }

    ~Instance() {
        std::cout << "SharedInstance has destructed" << std::endl; // To ensure that destructor is never called
    }
};

bool operator==(const Instance &a, const Instance &b) {
    return a.sharedAtomicState == b.sharedAtomicState;
}

int main(int argc, char **argv) {
    using namespace Lincheck;
    LincheckConfiguration<Instance, Instance> conf;
    conf.iterations(1);
    conf.invocationsPerIteration(10000);
    conf.minimizeFailedScenario(false);

    conf.operation<int, &Instance::inc, &Instance::atomic_inc>("inc");
    conf.operation<int, &Instance::dec, &Instance::atomic_dec>("dec");
    conf.operation<int, unsigned long, UnsignedLongGen, &Instance::add, &Instance::atomic_add>("add");
    conf.runTest();
    return 0;
}