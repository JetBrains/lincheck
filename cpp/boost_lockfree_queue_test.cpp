#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"
#include "lincheck_functions.h"
#include <queue>

#include <boost/lockfree/queue.hpp>

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
    boost::lockfree::queue<int> q = boost::lockfree::queue<int>(100);

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

using namespace Lincheck;

TEST(BoostLockfreeQueueTest, QueueTest) {
    LincheckConfiguration<ConcurrentQueueBoost, SequentialQueueBoost> conf;
    conf.iterations(100);
    conf.invocationsPerIteration(500);
    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(5);

    conf.operation<bool, int, &ConcurrentQueueBoost::push, &SequentialQueueBoost::push>("push");
    conf.operation<std::pair<bool, int>, &ConcurrentQueueBoost::pop, &SequentialQueueBoost::pop>("pop");
    ASSERT_EQ(conf.runTest(false), "");
}