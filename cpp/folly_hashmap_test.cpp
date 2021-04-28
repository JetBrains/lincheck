#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"

#include "folly/concurrency/ConcurrentHashMap.h"

class SequentialMapFolly {
public:
    std::unordered_map<int, int> map;

    bool assign(int key, int value) {
        map.insert_or_assign(key, value);
        return true; // concurrent version always returns true
    }

    int get(int key) {
        auto it = map.find(key);
        if (it != map.end()) {
            return it->second;
        }
        return -239;
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

    int get(int key) {
        auto it = map.find(key);
        if (it != map.end()) {
            return it->second;
        }
        return -239;
    }

    int erase(int key) {
        return map.erase(key);
    }
};

template<>
struct Lincheck::hash<std::vector<int>> {
    std::size_t operator()(const std::vector<int> &v) const noexcept {
        std::string s;
        for (auto elem : v) {
            s += std::to_string(elem) + ",";
        }
        return std::hash<std::string>()(s);
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

    conf.operation<bool, int, int, &ConcurrentMapFolly::assign, &SequentialMapFolly::assign>("assign");
    conf.operation<int, int, &ConcurrentMapFolly::get, &SequentialMapFolly::get>("get");
    conf.operation<int, int, &ConcurrentMapFolly::erase, &SequentialMapFolly::erase>("erase");
    ASSERT_EQ(conf.runTest(false), "");
}