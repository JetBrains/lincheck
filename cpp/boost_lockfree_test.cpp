#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"
#include "lincheck_functions.h"
#include <queue>
#include <stack>

#include <boost/lockfree/queue.hpp>
#include <boost/lockfree/spsc_queue.hpp>
#include <boost/lockfree/stack.hpp>

class SequentialQueueBoost {
public:
    std::queue<int> q;

    bool push(int value) {
        q.push(value);
        return true;
    }

    std::pair<bool, int> pop() {
        int val = 0;
        if(!q.empty()) {
            val = q.front();
            q.pop();
            return {true, val};
        } else {
            return {false, val};
        }
    }
};

class ConcurrentQueueBoost {
public:
    boost::lockfree::queue<int> q = boost::lockfree::queue<int>(1);

    bool push(int value) {
        return q.push(value);
    }

    std::pair<bool, int> pop() {
        int val = 0;
        bool success = q.pop(val);
        return {success, success ? val : 0};
    }
};

class ConcurrentSPSCQueueBoost {
public:
    boost::lockfree::spsc_queue<int> q = boost::lockfree::spsc_queue<int>(100); // fixed-size ring buffer

    bool push(int value) {
        return q.push(value);
    }

    std::pair<bool, int> pop() {
        int val = 0;
        bool success = q.pop(val);
        return {success, success ? val : 0};
    }
};

template<>
struct Lincheck::hash<SequentialQueueBoost> {
    std::size_t operator()(SequentialQueueBoost &s) const noexcept {
        std::vector<int> vec;
        while(!s.q.empty()) {
            vec.push_back(s.q.front());
            s.q.pop();
        }
        for (auto it : vec) {
            s.q.push(it);
        }
        return Lincheck::hash<std::vector<int>>()(vec);
    }
};

bool operator==(const SequentialQueueBoost &a, const SequentialQueueBoost &b) {
    return a.q == b.q;
}

class SequentialStackBoost {
public:
    std::stack<int> s;

    bool push(int value) {
        s.push(value);
        return true;
    }

    std::pair<bool, int> pop() {
        int val = 0;
        if(!s.empty()) {
            val = s.top();
            s.pop();
            return {true, val};
        } else {
            return {false, val};
        }
    }

    bool empty() {
        return s.empty();
    }
};

class ConcurrentStackBoost {
public:
    boost::lockfree::stack<int> s = boost::lockfree::stack<int>(1);

    bool push(int value) {
        return s.push(value);
    }

    std::pair<bool, int> pop() {
        int val = 0;
        bool success = s.pop(val);
        return {success, success ? val : 0};
    }

    bool empty() {
        return s.empty();
    }
};

template<>
struct Lincheck::hash<SequentialStackBoost> {
    std::size_t operator()(SequentialStackBoost &s) const noexcept {
        std::vector<int> vec;
        while(!s.s.empty()) {
            vec.push_back(s.s.top());
            s.s.pop();
        }
        reverse(vec.begin(), vec.end());
        for (auto it : vec) {
            s.s.push(it);
        }
        return Lincheck::hash<std::vector<int>>()(vec);
    }
};

bool operator==(const SequentialStackBoost &a, const SequentialStackBoost &b) {
    return a.s == b.s;
}

using namespace Lincheck;

TEST(BoostLockfreeTest, BadSequentialQueueTest) {
    LincheckConfiguration<SequentialQueueBoost, SequentialQueueBoost> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(3);

    conf.operation<bool, int, &SequentialQueueBoost::push, &SequentialQueueBoost::push>("push");
    conf.operation<std::pair<bool, int>, &SequentialQueueBoost::pop, &SequentialQueueBoost::pop>("pop");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(BoostLockfreeTest, BadSequentialStackTest) {
    LincheckConfiguration<SequentialStackBoost, SequentialStackBoost> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(3);

    conf.operation<bool, int, &SequentialStackBoost::push, &SequentialStackBoost::push>("push");
    conf.operation<std::pair<bool, int>, &SequentialStackBoost::pop, &SequentialStackBoost::pop>("pop");
    conf.operation<bool, &SequentialStackBoost::empty, &SequentialStackBoost::empty>("empty");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(BoostLockfreeTest, QueueTest) {
    LincheckConfiguration<ConcurrentQueueBoost, SequentialQueueBoost> conf;
    conf.iterations(10);
    conf.invocationsPerIteration(500);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(3);
    conf.actorsAfter(10);

    conf.operation<bool, int, &ConcurrentQueueBoost::push, &SequentialQueueBoost::push>("push");
    conf.operation<std::pair<bool, int>, &ConcurrentQueueBoost::pop, &SequentialQueueBoost::pop>("pop");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(BoostLockfreeTest, StackTest) {
    LincheckConfiguration<ConcurrentStackBoost, SequentialStackBoost> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(3);

    conf.operation<bool, int, &ConcurrentStackBoost::push, &SequentialStackBoost::push>("push");
    conf.operation<std::pair<bool, int>, &ConcurrentStackBoost::pop, &SequentialStackBoost::pop>("pop");
    conf.operation<bool, &ConcurrentStackBoost::empty, &SequentialStackBoost::empty>("empty");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(BoostLockfreeTest, BadSPSCQueueTest) {
    LincheckConfiguration<ConcurrentSPSCQueueBoost, SequentialQueueBoost> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(3);

    conf.operation<bool, int, &ConcurrentSPSCQueueBoost::push, &SequentialQueueBoost::push>("push");
    conf.operation<std::pair<bool, int>, &ConcurrentSPSCQueueBoost::pop, &SequentialQueueBoost::pop>("pop", "popNonParallelGroup");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(BoostLockfreeTest, SPSCQueueTest) {
    LincheckConfiguration<ConcurrentSPSCQueueBoost, SequentialQueueBoost> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(2);
    conf.actorsPerThread(3);

    conf.operation<bool, int, &ConcurrentSPSCQueueBoost::push, &SequentialQueueBoost::push>("push", "pushNonParallelGroup");
    conf.operation<std::pair<bool, int>, &ConcurrentSPSCQueueBoost::pop, &SequentialQueueBoost::pop>("pop", "popNonParallelGroup");
    ASSERT_EQ(conf.runTest(false), "");
}