#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "lincheck.h"
#include <junction/ConcurrentMap_Grampa.h>

class SequentialMap {
public:
    std::unordered_map<int, int> map;

    int assign(int key, int value) {
        return map[key] = value;
    }

    int get(int key) {
        auto it = map.find(key);
        if(it != map.end()) {
            return it->second;
        }
        return 0;
    }

    int exchange(int key, int value) {
        auto it = map.find(key);
        if(it != map.end()) {
            auto ans = it->second;
            it->second = value;
            return ans;
        }
        map[key] = value;
        return 0;
    }

    int erase(int key) {
        auto it = map.find(key);
        if(it != map.end()) {
            auto ans = it->second;
            map.erase(key);
            return ans;
        }
        map.erase(key);
        return 0;
    }
};

class ConcurrentMap {
public:
    junction::ConcurrentMap_Grampa<int, int> map;

    int assign(int key, int value) {
        std::cerr << "assign" << key << ", " << value << "\n";
        auto ans = map.assign(key, value);
        std::cerr << "assign2" << key << ", " << value << "\n";
        return ans;
    }

    int get(int key) {
        std::cerr << "get\n";
        auto mut = map.find(key);
        std::cerr << "get2\n";
        auto ans =  map.get(key);
        std::cerr << "get3\n";
        return ans;
    }

    int exchange(int key, int value) {
        std::cerr << "exchange\n";
        auto ans =  map.exchange(key, value);
        std::cerr << "exchange2\n";
        return ans;
    }

    int erase(int key) {
        std::cerr << "erase\n";
        auto ans =  map.erase(key);
        std::cerr << "erase2\n";
        return ans;
    }
};

template<>
struct Lincheck::hash<std::vector<int>> {
    std::size_t operator()(const std::vector<int> &v) const noexcept {
        std::string s;
        for(auto elem : v) {
            s += std::to_string(elem) + ",";
        }
        return std::hash<std::string>()(s);
    }
};

template<>
struct Lincheck::hash<SequentialMap> {
    std::size_t operator()(SequentialMap const &s) const noexcept {
        std::vector<int> vec;
        for(auto it : s.map) {
            vec.push_back(it.first);
            vec.push_back(it.second);
        }
        return Lincheck::hash<std::vector<int>>()(vec);
    }
};

bool operator==(const SequentialMap &a, const SequentialMap &b) {
    return a.map == b.map;
}

using namespace Lincheck;

void myAttach2() {
    std::cerr << "myattach2\n";
    junction::DefaultQSBR.createContext();
}

TEST(JunctionTest, FirstTest) {
    LincheckConfiguration<ConcurrentMap, SequentialMap> conf;
    conf.iterations(1);
    conf.invocationsPerIteration(500);
    conf.minimizeFailedScenario(false);
    conf.initThreadFunction<myAttach2>();
    //Not working right now, because of produce-consume(locks and waits until consumed previous value)
    //conf.operation<int, int, int, &ConcurrentMap::assign, &SequentialMap::assign>("assign");
    //conf.operation<int, int, &ConcurrentMap::get, &SequentialMap::get>("get");
    //conf.operation<int, int, int, &ConcurrentMap::exchange, &SequentialMap::exchange>("exchange");
    //conf.operation<int, int, &ConcurrentMap::erase, &SequentialMap::erase>("erase");
    ASSERT_EQ(conf.runTest(false), "");
}