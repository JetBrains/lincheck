#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"
#include "lincheck_functions.h"

#include "folly/concurrency/ConcurrentHashMap.h"
#include "folly/concurrency/DynamicBoundedQueue.h"
#include "folly/concurrency/UnboundedQueue.h"

class SequentialMapFolly {
public:
    std::unordered_map<int, int> map;

    SequentialMapFolly() {
        map.reserve(100); // to prevent from rehashing and crashes with SIGSEGV, SIGABRT, or new/delete issues
    }

    bool assign(int key, int value) {
        map.insert_or_assign(key, value);
        return true; // concurrent version always returns true
    }

    std::pair<bool, int> get(int key) {
        auto it = map.find(key);
        if (it != map.end()) {
            return {true, it->second};
        }
        return {false, 0};
    }

    int erase(int key) {
        return map.erase(key);
    }
};

class ConcurrentMapFolly {
public:
    folly::ConcurrentHashMap<int, int> map;

    bool assign(int key, int value) {
        return map.insert_or_assign(key, value).second;
    }

    std::pair<bool, int> get(int key) {
        auto it = map.find(key);
        if (it != map.end()) {
            return {true, it->second};
        }
        return {false, 0};
    }

    int erase(int key) {
        return map.erase(key);
    }
};

template<>
struct Lincheck::hash<SequentialMapFolly> {
    std::size_t operator()(SequentialMapFolly const &s) const noexcept {
        std::vector<int> vec;
        for (auto it : s.map) {
            vec.push_back(it.first);
            vec.push_back(it.second);
        }
        return Lincheck::hash<std::vector<int>>()(vec);
    }
};

bool operator==(const SequentialMapFolly &a, const SequentialMapFolly &b) {
    return a.map == b.map;
}

class SequentialQueueFolly {
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

class ConcurrentDynamicBoundedQueueFolly {
public:
    folly::DMPMCQueue<int, false> queue = folly::DMPMCQueue<int, false>(100);

    bool push(int val) {
        return queue.try_enqueue(val);
    }

    std::pair<bool, int> pop() {
        int ans = 0;
        bool success = queue.try_dequeue(ans);
        return {success, ans};
    }
};

class ConcurrentUnboundedQueueFolly {
public:
    folly::UMPMCQueue<int, false> queue;

    bool push(int val) {
        queue.enqueue(val);
        return true;
    }

    std::pair<bool, int> pop() {
        int ans = 0;
        bool success = queue.try_dequeue(ans);
        return {success, ans};
    }
};

template<>
struct Lincheck::hash<SequentialQueueFolly> {
    std::size_t operator()(SequentialQueueFolly &s) const noexcept {
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

bool operator==(const SequentialQueueFolly &a, const SequentialQueueFolly &b) {
    return a.q == b.q;
}

using namespace Lincheck;

TEST(FollyTest, BadSequentialMapTest) {
    LincheckConfiguration<SequentialMapFolly, SequentialMapFolly> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, int, &SequentialMapFolly::assign, &SequentialMapFolly::assign>("assign");
    conf.operation<std::pair<bool, int>, int, &SequentialMapFolly::get, &SequentialMapFolly::get>("get");
    conf.operation<int, int, &SequentialMapFolly::erase, &SequentialMapFolly::erase>("erase");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(FollyTest, BadSequentialQueueTest) {
    LincheckConfiguration<SequentialQueueFolly, SequentialQueueFolly> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, &SequentialQueueFolly::push, &SequentialQueueFolly::push>("push");
    conf.operation<std::pair<bool, int>, &SequentialQueueFolly::pop, &SequentialQueueFolly::pop>("pop");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(FollyTest, HashMapTest) {
    LincheckConfiguration<ConcurrentMapFolly, SequentialMapFolly> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, int, &ConcurrentMapFolly::assign, &SequentialMapFolly::assign>("assign");
    conf.operation<std::pair<bool, int>, int, &ConcurrentMapFolly::get, &SequentialMapFolly::get>("get");
    conf.operation<int, int, &ConcurrentMapFolly::erase, &SequentialMapFolly::erase>("erase");
    ASSERT_EQ(conf.runTest(false), "");
}

TEST(FollyTest, DynamicBoundedQueueTest) {
    LincheckConfiguration<ConcurrentDynamicBoundedQueueFolly, SequentialQueueFolly> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, &ConcurrentDynamicBoundedQueueFolly::push, &SequentialQueueFolly::push>("push");
    conf.operation<std::pair<bool, int>, &ConcurrentDynamicBoundedQueueFolly::pop, &SequentialQueueFolly::pop>("pop");
    ASSERT_EQ(conf.runTest(false), "");
}


TEST(FollyTest, UnboundedQueueTest) {
    LincheckConfiguration<ConcurrentUnboundedQueueFolly, SequentialQueueFolly> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, &ConcurrentUnboundedQueueFolly::push, &SequentialQueueFolly::push>("push");
    conf.operation<std::pair<bool, int>, &ConcurrentUnboundedQueueFolly::pop, &SequentialQueueFolly::pop>("pop");
    ASSERT_EQ(conf.runTest(false), "");
}