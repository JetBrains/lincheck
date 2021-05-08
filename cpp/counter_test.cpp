#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"

class ComplexArg {
public:
    int value;

    ComplexArg() {
        value = 29;
    }
};

template<>
struct Lincheck::to_string<ComplexArg> {
    std::string operator()(const ComplexArg &arg) const noexcept {
        return std::to_string(arg.value);
    }
};

template<>
class Lincheck::ParameterGenerator<ComplexArg> {
public:
    ComplexArg generate() {
        return ComplexArg();
    }
};

class Counter {
public:
    int sharedState = 0;
    std::atomic_int sharedAtomicState = 0;
    int counter_validate_invocations = 0;

    int inc() {
        auto ans = ++sharedState;
        return ans;
    }

    int dec() {
        auto ans = --sharedState;
        return ans;
    }

    int add(int value) {
        auto ans = sharedState += value;
        return ans;
    }

    int double_op(int value1, ComplexArg value2) {
        return 0;
    }

    int atomic_inc() {
        return ++sharedAtomicState;
    }

    int atomic_dec() {
        return --sharedAtomicState;
    }

    int atomic_add(int value) {
        //std::cout << "atomic_add " << value << " started" << std::endl;
        auto ans = sharedAtomicState += value;
        //std::cout << "atomic_add " << value << " finished" << std::endl;
        return ans;
    }

    void validate_no_error() {}

    void validate_with_error() {
        counter_validate_invocations++;
        if (counter_validate_invocations == 5) {
            throw std::runtime_error("ValidationRuntimeError");
        }
    }
};

template<>
struct Lincheck::hash<Counter> {
    std::size_t operator()(Counter const &s) const noexcept {
        return Lincheck::hash<int>()(s.sharedAtomicState);
    }
};

bool operator==(const Counter &a, const Counter &b) {
    return a.sharedAtomicState == b.sharedAtomicState;
}

using namespace Lincheck;
using ::testing::HasSubstr;

TEST(CounterTest, BadInc) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.iterations(10);

    conf.actorsPerThread(5);
    conf.operation<int, &Counter::inc, &Counter::atomic_inc>("inc");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(CounterTest, BadDec) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.operation<int, &Counter::dec, &Counter::atomic_dec>("dec");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(CounterTest, BadAdd) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.operation<int, int, &Counter::add, &Counter::atomic_add>("add");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(CounterTest, GoodDoubleOp) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.threads(3);
    conf.operation<int, int, ComplexArg, &Counter::double_op, &Counter::double_op>("double_op");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(CounterTest, GoodAtomicInc) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.threads(3);
    conf.operation<int, &Counter::atomic_inc, &Counter::atomic_inc>("inc");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(CounterTest, GoodAtomicDec) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.threads(3);
    conf.operation<int, &Counter::atomic_dec, &Counter::atomic_dec>("dec");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(CounterTest, GoodAtomicAdd) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.threads(3);
    conf.operation<int, int, &Counter::atomic_add, &Counter::atomic_add>("add");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(CounterTest, TEST_SHOULD_FAIL_ValidateFunctionsTest) {
    LincheckConfiguration<Counter, Counter> conf;
    conf.iterations(1);
    conf.invocationsPerIteration(1);
    conf.threads(2);
    conf.validationFunction<&Counter::validate_no_error>();
    conf.validationFunction<&Counter::validate_with_error>();
    conf.operation<int, int, &Counter::atomic_add, &Counter::atomic_add>("add");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(ConterTest, EnabledVerifierTest) {
    // This test is needed to compare speed with DisabledVerifierTest
    LincheckConfiguration<Counter, Counter> conf;

    conf.iterations(25);
    conf.invocationsPerIteration(10);
    conf.threads(10);
    conf.actorsPerThread(10);
    conf.operation<int, int, &Counter::atomic_add, &Counter::atomic_add>("add");

    ASSERT_EQ(conf.runTest(false), "");
}

TEST(CounterTest, DisabledVerifierTest) {
    // Compare speed with EnabledVerifierTest
    LincheckConfiguration<Counter, Counter> conf;
    conf.iterations(25);
    conf.invocationsPerIteration(10);
    // a lot of threads and parallel actors
    conf.threads(10);
    conf.actorsPerThread(10);
    conf.operation<int, int, &Counter::atomic_add, &Counter::atomic_add>("add");

    conf.disableVerifier();
    ASSERT_EQ(conf.runTest(false), ""); // This should be incredibly fast because of disabled verifier
}