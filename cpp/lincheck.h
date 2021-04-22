#pragma once

#include <random>
#include "../build/bin/native/debugShared/libnative_api.h"

extern libnative_ExportedSymbols *libnative_symbols(void);

namespace Lincheck {
    class UnsignedLongGen {
        std::mt19937 rnd;
    public:
        unsigned long generate() {
            return rnd();
        }
    };

    template<typename TestClass, typename SequentialSpecification>
    class LincheckConfiguration {
        libnative_ExportedSymbols *lib = libnative_symbols();
        libnative_kref_org_jetbrains_kotlinx_lincheck_NativeAPIStressConfiguration configuration = lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.NativeAPIStressConfiguration();

        typedef void *(*constructor_pointer)();

        typedef void (*destructor_pointer)(void *);

        typedef bool (*equals_pointer)(void *, void *);

        typedef int (*hashCode_pointer)(void *);

    public:
        LincheckConfiguration() {
            constructor_pointer *constructor = new constructor_pointer();
            *constructor = []() -> void * { return new TestClass(); };

            destructor_pointer *destructor = new destructor_pointer();
            *destructor = [](void *p) { delete (TestClass *) p; };

            equals_pointer *equals = new equals_pointer();
            *equals = [](void *a, void *b) -> bool { return *(TestClass *) a == *(TestClass *) b; };

            hashCode_pointer *hashCode = new hashCode_pointer();
            *hashCode = [](void *) -> int { return 0; };

            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupInitialStateAndSequentialSpecification(
                    configuration,
                    (void *) *constructor, // constructor
                    (void *) *destructor, // destructor
                    (void *) *equals, // equals
                    (void *) *hashCode // hashCode
            );
        }

        void iterations(int count) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupIterations(
                    configuration, count);
        }

        void invocationsPerIteration(int count) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupInvocationsPerIteration(
                    configuration, count);
        }

        void minimizeFailedScenario(bool minimizeFailedScenario) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupMinimizeFailedScenario(
                    configuration, minimizeFailedScenario);
        }

        template<typename Ret, Ret (TestClass::*op)(), Ret (TestClass::*seq_spec)()>
        void operation(const char *operationName, bool useOnce = false) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupOperation1(
                    configuration,
                    (void *) (void *(*)(void *)) [](void *instance) -> void * { // operation
                        auto *obj = (TestClass *) instance; // add type to void*
                        Ret res = (obj->*op)(); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void *(*)(void *)) [](void *instance) -> void * { // sequential specification
                        auto *obj = (SequentialSpecification *) instance; // add type to void*
                        Ret res = (obj->*seq_spec)(); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void (*)(void *)) [](void *ret) { // Ret destructor
                        Ret *obj = (Ret *) ret; // add type to void*
                        delete obj; // destructor
                    },
                    (void *) (bool (*)(void *, void *)) [](void *a, void *b) -> bool { // Ret equals
                        Ret *obj_a = (Ret *) a; // add type to void*
                        Ret *obj_b = (Ret *) b; // add type to void*
                        return *obj_a == *obj_b;
                    },
                    (void *) (int (*)(void *)) [](void *) -> int { // Ret hashCode
                        return 0;
                    },
                    (void *) (int (*)(void *)) [](void *ret) -> int { // Ret toString
                        return (int) (*(Ret *) ret);
                    },
                    operationName,
                    useOnce
            );
        }

        template<typename Ret, typename Arg1, typename Arg1Gen, Ret (TestClass::*op)(Arg1), Ret (TestClass::*seq_spec)(
                Arg1)>
        void operation(const char *operationName, bool useOnce = false) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupOperation2(
                    configuration,
                    (void *) (void *(*)()) []() -> void * { // arg1_gen_initial_state
                        return new Arg1Gen();
                    },
                    (void *) (void *(*)(void *)) [](void *gen) -> void * { // arg1_gen_generate
                        auto *obj = (Arg1Gen *) gen; // add type to void*
                        Arg1 arg = obj->generate(); // invoke generate method
                        return new Arg1(arg); // copy from stack to heap and return
                    },
                    (void *) (int (*)(void *)) [](void *arg) -> int { // arg1_toString
                        return (int) (*(Arg1 *) arg); // cast to int
                    },
                    (void *) (void *(*)(void *, void *)) [](void *instance, void *arg1) -> void * { // operation
                        auto *obj = (TestClass *) instance; // add type to void*
                        auto *a1 = (Arg1 *) arg1;
                        Ret res = (obj->*op)(*a1); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void *(*)(void *, void *)) [](void *instance,
                                                            void *arg1) -> void * { // sequential specification
                        auto *obj = (SequentialSpecification *) instance; // add type to void*
                        auto *a1 = (Arg1 *) arg1;
                        Ret res = (obj->*seq_spec)(*a1); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void (*)(void *)) [](void *ret) { // Ret destructor
                        Ret *obj = (Ret *) ret; // add type to void*
                        delete obj; // destructor
                    },
                    (void *) (bool (*)(void *, void *)) [](void *a, void *b) -> bool { // Ret equals
                        Ret *obj_a = (Ret *) a; // add type to void*
                        Ret *obj_b = (Ret *) b; // add type to void*
                        return *obj_a == *obj_b;
                    },
                    (void *) (int (*)(void *)) [](void *) -> int { // Ret hashCode
                        return 0;
                    },
                    (void *) (int (*)(void *)) [](void *ret) -> int { // Ret toString
                        return (int) (*(Ret *) ret);
                    },
                    operationName,
                    useOnce
            );
        }

        void runTest() {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.runNativeTest(configuration);
        }
    };
}