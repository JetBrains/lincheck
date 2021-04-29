#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"
#include "lincheck_functions.h"

#include "folly/concurrency/ConcurrentHashMap.h"

class SequentialMapFolly {
public:
    std::unordered_map<int, int> map;

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

using namespace Lincheck;

TEST(follyHashMapTest, FirstTest) {
    LincheckConfiguration<ConcurrentMapFolly, SequentialMapFolly> conf;
    conf.iterations(10);
    conf.invocationsPerIteration(500);
    conf.minimizeFailedScenario(false);
    conf.threads(4);
    conf.actorsPerThread(7);

    conf.operation<bool, int, int, &ConcurrentMapFolly::assign, &SequentialMapFolly::assign>("assign");
    conf.operation<std::pair<bool, int>, int, &ConcurrentMapFolly::get, &SequentialMapFolly::get>("get");
    conf.operation<int, int, &ConcurrentMapFolly::erase, &SequentialMapFolly::erase>("erase");
    ASSERT_EQ(conf.runTest(false), "");
}