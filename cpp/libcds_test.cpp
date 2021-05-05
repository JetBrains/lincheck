#include <gtest/gtest.h>
#include <gmock/gmock.h>
#include <cds/gc/dhp.h>
#include <cds/init.h>
#include <cds/container/msqueue.h>
#include <cds/container/treiber_stack.h>
#include <cds/container/fcstack.h>
#include <queue>
#include <stack>
#include "lincheck.h"
#include "lincheck_functions.h"

class SequentialQueueLibcds {
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

template<typename GC>
class ConcurrentQueueLibcds {
public:
    cds::container::MSQueue<GC, int> queue;

    bool push(int val) {
        return queue.enqueue(val);
    }

    std::pair<bool, int> pop() {
        int ans = 0;
        bool success = queue.dequeue(ans);
        return {success, ans};
    }
};

template<>
struct Lincheck::hash<SequentialQueueLibcds> {
    std::size_t operator()(SequentialQueueLibcds &s) const noexcept {
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

bool operator==(const SequentialQueueLibcds &a, const SequentialQueueLibcds &b) {
    return a.q == b.q;
}

class SequentialStackLibcds {
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

    bool clear() {
        while(!s.empty()) {
            s.pop();
        }
        return true;
    }
};

template<typename GC>
class ConcurrentTreiberStackLibcds {
public:
    cds::container::TreiberStack<GC, int> s;

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

    bool clear() {
        s.clear();
        return true;
    }
};

class ConcurrentFCStackLibcds {
public:
    cds::container::FCStack<int> s;

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

    bool clear() {
        s.clear();
        return true;
    }
};

template<>
struct Lincheck::hash<SequentialStackLibcds> {
    std::size_t operator()(SequentialStackLibcds &s) const noexcept {
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

bool operator==(const SequentialStackLibcds &a, const SequentialStackLibcds &b) {
    return a.s == b.s;
}

void myAttach() {
    //std::string val = "attached " + std::to_string(cds::OS::get_current_thread_id()) + "\n";
    //std::cout << val;
    cds::threading::Manager::attachThread();
}

void myDetach() {
    //std::string val = "detached " + std::to_string(cds::OS::get_current_thread_id()) + "\n";
    //std::cout << val;
    cds::threading::Manager::detachThread();
}

using namespace Lincheck;


TEST(LibcdsTest, BadSequentialQueueTest) {
    LincheckConfiguration<SequentialQueueLibcds, SequentialQueueLibcds> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, &SequentialQueueLibcds::push, &SequentialQueueLibcds::push>("push");
    conf.operation<std::pair<bool, int>, &SequentialQueueLibcds::pop, &SequentialQueueLibcds::pop>("pop");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(LibcdsTest, BadSequentialStackTest) {
    LincheckConfiguration<SequentialStackLibcds, SequentialStackLibcds> conf;
    conf.iterations(10);

    conf.minimizeFailedScenario(false);
    conf.threads(3);
    conf.actorsPerThread(4);

    conf.operation<bool, int, &SequentialStackLibcds::push, &SequentialStackLibcds::push>("push");
    conf.operation<std::pair<bool, int>, &SequentialStackLibcds::pop, &SequentialStackLibcds::pop>("pop");
    conf.operation<bool, &SequentialStackLibcds::clear, &SequentialStackLibcds::clear>("clear");
    ASSERT_THAT(conf.runTest(false), ::testing::HasSubstr("Invalid execution results"));
}

TEST(LibcdsTest, ConcurrentQueueHPTest) {
    cds::Initialize();
    {
        cds::gc::HP dhpGC(
                16
        );
        myAttach();
        LincheckConfiguration<ConcurrentQueueLibcds<cds::gc::HP>, SequentialQueueLibcds> conf;
        conf.iterations(10);
        conf.minimizeFailedScenario(false);
        conf.threads(3);
        conf.actorsPerThread(4);
        conf.initThreadFunction<myAttach>();
        conf.finishThreadFunction<myDetach>();
        conf.operation<bool, int, &ConcurrentQueueLibcds<cds::gc::HP>::push, &SequentialQueueLibcds::push>("push");
        conf.operation<std::pair<bool, int>, &ConcurrentQueueLibcds<cds::gc::HP>::pop, &SequentialQueueLibcds::pop>("pop");
        ASSERT_EQ(conf.runTest(false), "");
        myDetach();
    }
    cds::Terminate();
}

TEST(LibcdsTest, ConcurrentQueueDHPTest) {
    cds::Initialize();
    {
        cds::gc::DHP dhpGC(
                160 //dhp_init_guard_count
        );
        myAttach();
        LincheckConfiguration<ConcurrentQueueLibcds<cds::gc::DHP>, SequentialQueueLibcds> conf;
        conf.iterations(10);
        conf.minimizeFailedScenario(false);
        conf.threads(3);
        conf.actorsPerThread(4);
        conf.initThreadFunction<myAttach>();
        conf.finishThreadFunction<myDetach>();
        conf.operation<bool, int, &ConcurrentQueueLibcds<cds::gc::DHP>::push, &SequentialQueueLibcds::push>("push");
        conf.operation<std::pair<bool, int>, &ConcurrentQueueLibcds<cds::gc::DHP>::pop, &SequentialQueueLibcds::pop>("pop");
        ASSERT_EQ(conf.runTest(false), "");
        myDetach();
    }
    cds::Terminate();
}

TEST(LibcdsTest, ConcurrentTreiberStackHPTest) {
    cds::Initialize();
    {
        cds::gc::HP dhpGC(
                16
        );
        myAttach();
        LincheckConfiguration<ConcurrentTreiberStackLibcds<cds::gc::HP>, SequentialStackLibcds> conf;
        conf.iterations(10);
        conf.minimizeFailedScenario(false);
        conf.threads(3);
        conf.actorsPerThread(4);
        conf.initThreadFunction<myAttach>();
        conf.finishThreadFunction<myDetach>();
        conf.operation<bool, int, &ConcurrentTreiberStackLibcds<cds::gc::HP>::push, &SequentialStackLibcds::push>("push");
        conf.operation<std::pair<bool, int>, &ConcurrentTreiberStackLibcds<cds::gc::HP>::pop, &SequentialStackLibcds::pop>("pop");
        conf.operation<bool, &ConcurrentTreiberStackLibcds<cds::gc::HP>::clear, &SequentialStackLibcds::clear>("clear");
        ASSERT_EQ(conf.runTest(false), "");
        myDetach();
    }
    cds::Terminate();
}

TEST(LibcdsTest, ConcurrentTreiberStackDHPTest) {
    cds::Initialize();
    {
        cds::gc::DHP dhpGC(
                160
        );
        myAttach();
        LincheckConfiguration<ConcurrentTreiberStackLibcds<cds::gc::DHP>, SequentialStackLibcds> conf;
        conf.iterations(10);
        conf.minimizeFailedScenario(false);
        conf.threads(3);
        conf.actorsPerThread(4);
        conf.initThreadFunction<myAttach>();
        conf.finishThreadFunction<myDetach>();
        conf.operation<bool, int, &ConcurrentTreiberStackLibcds<cds::gc::DHP>::push, &SequentialStackLibcds::push>("push");
        conf.operation<std::pair<bool, int>, &ConcurrentTreiberStackLibcds<cds::gc::DHP>::pop, &SequentialStackLibcds::pop>("pop");
        conf.operation<bool, &ConcurrentTreiberStackLibcds<cds::gc::DHP>::clear, &SequentialStackLibcds::clear>("clear");
        ASSERT_EQ(conf.runTest(false), "");
        myDetach();
    }
    cds::Terminate();
}

/*
TEST(LibcdsTest, ConcurrentFCStackTest) {
    cds::Initialize();
    {
        LincheckConfiguration<ConcurrentFCStackLibcds, SequentialStackLibcds> conf;
        conf.iterations(1);
        conf.minimizeFailedScenario(false);
        conf.threads(3);
        conf.actorsPerThread(4);
        conf.operation<bool, int, &ConcurrentFCStackLibcds::push, &SequentialStackLibcds::push>("push");
        conf.operation<std::pair<bool, int>, &ConcurrentFCStackLibcds::pop, &SequentialStackLibcds::pop>("pop");
        conf.operation<bool, &ConcurrentFCStackLibcds::clear, &SequentialStackLibcds::clear>("clear");
        ASSERT_EQ(conf.runTest(false), "");
    }
    cds::Terminate();
}
*/