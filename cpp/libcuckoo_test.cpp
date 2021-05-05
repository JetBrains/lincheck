#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"
#include "lincheck_functions.h"

#include "libcuckoo/cuckoohash_map.hh"

class SequentialMapCuckoo {
public:
    std::unordered_map<int, int> map;

    SequentialMapCuckoo() {
        map.reserve(100);
    }

    bool assign(int key, int value) {
        //std::cerr << "seqassign " << key << ", " << value << "\n";
        auto it = map.find(key);
        if (it != map.end()) {
            map[key] = value;
            return false;
        }
        map[key] = value;
        return true;
    }

    int get(int key) {
        //std::cerr << "seqget " << key << "\n";
        auto it = map.find(key);
        if (it != map.end()) {
            return it->second;
        }
        return -239;
    }

    bool erase(int key) {
        //std::cerr << "seqerase " << key << "\n";
        auto it = map.find(key);
        if (it != map.end()) {
            map.erase(key);
            return true;
        }
        map.erase(key);
        return false;
    }
};

class ConcurrentMapCuckoo {
public:
    libcuckoo::cuckoohash_map<int, int> map;

    bool assign(int key, int value) {
        //std::cerr << "assign " << key << ", " << value << "\n";
        auto ans = map.insert_or_assign(key, value);
        //std::cerr << "assign2 " << key << ", " << value << "\n";
        return ans;
    }

    int get(int key) {
        //std::cerr << "get " << key << "\n";
        try {
            auto ans = map.find(key);
            //std::cerr << "get2 " << key << "\n";
            return ans;
        } catch (std::out_of_range &e) {
            //std::cerr << "get3 " << key << "\n";
            return -239;
        }
    }

    bool erase(int key) {
        //std::cerr << "erase " << key << "\n";
        auto ans = map.erase(key);
        //std::cerr << "erase2 " << key << "\n";
        return ans;
    }
};

template<>
struct Lincheck::hash<SequentialMapCuckoo> {
    std::size_t operator()(SequentialMapCuckoo const &s) const noexcept {
        std::vector<int> vec;
        for (auto it : s.map) {
            vec.push_back(it.first);
            vec.push_back(it.second);
        }
        return Lincheck::hash<std::vector<int>>()(vec);
    }
};

bool operator==(const SequentialMapCuckoo &a, const SequentialMapCuckoo &b) {
    return a.map == b.map;
}

using namespace Lincheck;

TEST(LibcuckooTest, BadSequentialMapTest) {
    LincheckConfiguration<SequentialMapCuckoo, SequentialMapCuckoo> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, int, &SequentialMapCuckoo::assign, &SequentialMapCuckoo::assign>("assign");
    conf.operation<int, int, &SequentialMapCuckoo::get, &SequentialMapCuckoo::get>("get");
    conf.operation<bool, int, &SequentialMapCuckoo::erase, &SequentialMapCuckoo::erase>("erase");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(LibcuckooTest, HashMapTest) {
    LincheckConfiguration<ConcurrentMapCuckoo, SequentialMapCuckoo> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, int, &ConcurrentMapCuckoo::assign, &SequentialMapCuckoo::assign>("assign");
    conf.operation<int, int, &ConcurrentMapCuckoo::get, &SequentialMapCuckoo::get>("get");
    conf.operation<bool, int, &ConcurrentMapCuckoo::erase, &SequentialMapCuckoo::erase>("erase");
    ASSERT_EQ(conf.runTest(false), "");
}